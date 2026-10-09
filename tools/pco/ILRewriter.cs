using System.Reflection;
using System.Reflection.Emit;

namespace PaccManager.Pco;

/// <summary>
/// CIL 指令的读写与偏移修正框架。
///
/// <para>PCO 前几个版本只改 #Strings 堆里的名字字节，从不动 IL，所以不需要理解指令。
/// 字符串加密、控制流平坦化、反调试注入都要改方法体，改完方法体的字节长度会变，
/// 所有分支/switch 的相对偏移随之失效——这里把这件事一次性做对：</para>
///
/// <list type="bullet">
/// <item>读：顺序解析出每条指令的偏移与操作数，分支目标一律换算成「目标处的绝对偏移」。</item>
/// <item>写：用标签（<see cref="IlLabel"/>）表达跳转目标，先算标签落点再回填相对偏移，
/// 短跳转一律升格为长跳转——长度恒定，一次遍历即可定偏移，不必做收敛迭代。</item>
/// </list>
///
/// <para>指令表不手抄，直接从 <see cref="OpCodes"/> 反射得到，省得漏一条就整段解析错位。</para>
/// </summary>
internal static class IlOpcodes
{
    private readonly record struct Def(OperandType Read, ushort Canonical, OperandType Write);

    private static readonly Dictionary<ushort, Def> Map = Build();

    private static readonly Dictionary<string, ushort> Named = BuildNamed();

    private static readonly Dictionary<ushort, OpCode> Ops = BuildOps();

    /// <summary>按操作码取 <see cref="OpCode"/> 结构，栈行为分析要用它。</summary>
    public static OpCode OpCodeOf(ushort code) =>
        Ops.TryGetValue(code, out OpCode op)
            ? op
            : throw new InvalidOperationException($"未知 IL 操作码 0x{code:X4}");

    private static Dictionary<ushort, OpCode> BuildOps()
    {
        var map = new Dictionary<ushort, OpCode>();
        foreach (FieldInfo f in typeof(OpCodes).GetFields(BindingFlags.Public | BindingFlags.Static))
        {
            if (f.FieldType == typeof(OpCode))
            {
                var op = (OpCode)f.GetValue(null)!;
                map[unchecked((ushort)op.Value)] = op;
            }
        }
        return map;
    }

    /// <summary>按指令助记符取操作码（如 "ldarg.0"、"ldc.i4.s"），注入方法体时不必手抄魔数。</summary>
    public static ushort Code(string name) =>
        Named.TryGetValue(name, out ushort code)
            ? code
            : throw new InvalidOperationException($"未知 IL 操作码 {name}");

    private static Dictionary<string, ushort> BuildNamed()
    {
        // OpCodes 的字段名是 C# 标识符（Ldarg_0），助记符是 IL 写法（ldarg.0）。
        // 小写 + 下划线换点号即可对上：Ldc_I4_S -> ldc.i4.s，Br_S -> br.s。
        var map = new Dictionary<string, ushort>(StringComparer.Ordinal);
        foreach (FieldInfo f in typeof(OpCodes).GetFields(BindingFlags.Public | BindingFlags.Static))
        {
            if (f.FieldType == typeof(OpCode))
            {
                map[f.Name.ToLowerInvariant().Replace('_', '.')] =
                    unchecked((ushort)((OpCode)f.GetValue(null)!).Value);
            }
        }
        return map;
    }

    /// <summary>读指令用的操作数类型（按原始操作码，短格式就是短格式）。</summary>
    public static bool TryRead(ushort code, out OperandType operand) =>
        Lookup(code, out _, out operand);

    /// <summary>写指令用的规范操作码与操作数类型（短跳转/短变址/ldc.i4.s 一律升格为长格式）。</summary>
    public static bool TryWrite(ushort code, out ushort canonical, out OperandType operand)
    {
        if (!Map.TryGetValue(code, out Def def))
        {
            canonical = 0;
            operand = OperandType.InlineNone;
            return false;
        }
        canonical = def.Canonical;
        operand = def.Write;
        return true;
    }

    private static bool Lookup(ushort code, out ushort canonical, out OperandType operand)
    {
        if (Map.TryGetValue(code, out Def def))
        {
            canonical = def.Canonical;
            operand = def.Read;
            return true;
        }
        canonical = 0;
        operand = OperandType.InlineNone;
        return false;
    }

    public static string Name(ushort code)
    {
        foreach (FieldInfo f in typeof(OpCodes).GetFields(BindingFlags.Public | BindingFlags.Static))
        {
            var op = (OpCode)f.GetValue(null)!;
            if (unchecked((ushort)op.Value) == code)
            {
                return f.Name;
            }
        }
        return $"0x{code:X4}";
    }

    private static Dictionary<ushort, Def> Build()
    {
        var map = new Dictionary<ushort, Def>();
        foreach (FieldInfo f in typeof(OpCodes).GetFields(BindingFlags.Public | BindingFlags.Static))
        {
            if (f.FieldType != typeof(OpCode))
            {
                continue;
            }
            var op = (OpCode)f.GetValue(null)!;
            ushort code = unchecked((ushort)op.Value);
            ushort canonical = Canonical(code);
            // 规范操作数类型取自规范操作码自己的定义（长格式），这样 ldc.i4.s -> ldc.i4、
            // br.s -> br、ldloc.s -> ldloc 的宽度都自然变宽，写的时候不必特判。
            OperandType write = canonical == code
                ? op.OperandType
                : OperandTypeOf(canonical);
            map[code] = new Def(op.OperandType, canonical, write);
        }
        return map;
    }

    private static OperandType OperandTypeOf(ushort code)
    {
        foreach (FieldInfo f in typeof(OpCodes).GetFields(BindingFlags.Public | BindingFlags.Static))
        {
            var op = (OpCode)f.GetValue(null)!;
            if (unchecked((ushort)op.Value) == code)
            {
                return op.OperandType;
            }
        }
        throw new InvalidOperationException($"未知操作码 0x{code:X4}");
    }

    /// <summary>短格式到长格式的映射；不在表里的返回自身。</summary>
    private static ushort Canonical(ushort code) => code switch
    {
        0x2B => 0x38, // br.s      -> br
        0x2C => 0x39, // brfalse.s -> brfalse
        0x2D => 0x3A, // brtrue.s  -> brtrue
        0x2E => 0x3B, // beq.s     -> beq
        0x2F => 0x3C, // bge.s     -> bge
        0x30 => 0x3D, // bgt.s     -> bgt
        0x31 => 0x3E, // ble.s     -> ble
        0x32 => 0x3F, // blt.s     -> blt
        0x33 => 0x40, // bne.un.s  -> bne.un
        0x34 => 0x41, // bge.un.s  -> bge.un
        0x35 => 0x42, // bgt.un.s  -> bgt.un
        0x36 => 0x43, // ble.un.s  -> ble.un
        0x37 => 0x44, // blt.un.s  -> blt.un
        0xDE => 0xDD, // leave.s   -> leave
        0x1F => 0x20, // ldc.i4.s  -> ldc.i4
        0x0E => 0xFE09, // ldarg.s  -> ldarg
        0x0F => 0xFE0A, // ldarga.s -> ldarga
        0x10 => 0xFE0B, // starg.s  -> starg
        0x11 => 0xFE0C, // ldloc.s  -> ldloc
        0x12 => 0xFE0D, // ldloca.s -> ldloca
        0x13 => 0xFE0E, // stloc.s  -> stloc
        _ => code,
    };

    public static int OperandSize(OperandType t) => t switch
    {
        OperandType.InlineNone => 0,
        OperandType.ShortInlineI or OperandType.ShortInlineVar or OperandType.ShortInlineBrTarget => 1,
        OperandType.InlineVar => 2,
        OperandType.InlineI or OperandType.ShortInlineR or OperandType.InlineBrTarget
            or OperandType.InlineField or OperandType.InlineMethod or OperandType.InlineSig
            or OperandType.InlineString or OperandType.InlineTok or OperandType.InlineType => 4,
        OperandType.InlineI8 or OperandType.InlineR => 8,
        _ => -1, // InlineSwitch：变长，另行处理
    };

    /// <summary>
    /// 指令在规范（长格式）下的总字节数，含操作码本身。
    /// switch 是变长指令，必须按实际目标个数算——传 null 会当成零个目标，导致后续偏移全部错位。
    /// </summary>
    public static int CanonicalSize(ushort code, object? operand = null)
    {
        ushort canonical = Canonical(code);
        int head = canonical > 0xFF ? 2 : 1;
        OperandType kind = OperandTypeOf(canonical);
        if (kind == OperandType.InlineSwitch)
        {
            int count = operand switch
            {
                IlLabel[] labels => labels.Length,
                int[] targets => targets.Length,
                _ => 0,
            };
            return head + 4 + 4 * count;
        }
        return head + OperandSize(kind);
    }

    /// <summary>指令在原始（可能短格式）下的总字节数，含操作码本身。用于反推旧方法体长度。</summary>
    public static int SizeOf(ushort code, object? operand)
    {
        if (!Map.TryGetValue(code, out Def def))
        {
            throw new BadImageFormatException($"未知 IL 操作码 0x{code:X4}");
        }
        int head = code > 0xFF ? 2 : 1;
        if (def.Read == OperandType.InlineSwitch)
        {
            return head + 4 + 4 * ((int[])operand!).Length;
        }
        return head + OperandSize(def.Read);
    }
}

/// <summary>跳转目标占位。写方法体时先记标签，再在回填阶段换算成相对偏移。</summary>
internal sealed class IlLabel
{
    public int Offset = -1;
}

/// <summary>一条 IL 指令。分支/switch 的操作数是 <see cref="IlLabel"/>（或数组），其余是原始值。</summary>
internal sealed class IlInstruction
{
    public ushort OpCode;

    /// <summary>读取阶段：分支为「绝对目标偏移」int/switch 为 int[]；写入阶段：<see cref="IlLabel"/> / IlLabel[]。</summary>
    public object? Operand;

    public int Offset;

    public bool IsBranch => IlOpcodes.TryRead(OpCode, out OperandType t)
        && (t == OperandType.InlineBrTarget || t == OperandType.ShortInlineBrTarget);

    public bool IsSwitch => IlOpcodes.TryRead(OpCode, out OperandType t) && t == OperandType.InlineSwitch;

    /// <summary>读取阶段分支目标（绝对偏移）；非分支返回 -1。</summary>
    public int BranchTarget => Operand is int t ? t : -1;
}

/// <summary>
/// 方法体里的一条异常处理子句。
///
/// <para>不用 <c>ExceptionRegion</c>：它没有公开构造函数，变换改完方法体长度后要重算边界，
/// 却造不出新的一条。这里用可构造的等价模型，写回时再按字段摊回 encoder。</para>
/// </summary>
internal sealed class IlException
{
    public required System.Reflection.Metadata.ExceptionRegionKind Kind { get; init; }
    public required int TryOffset { get; init; }
    public required int TryLength { get; init; }
    public required int HandlerOffset { get; init; }
    public required int HandlerLength { get; init; }
    public required System.Reflection.Metadata.EntityHandle CatchType { get; init; }

    public static IlException From(System.Reflection.Metadata.ExceptionRegion r) => new()
    {
        Kind = r.Kind,
        TryOffset = r.TryOffset,
        TryLength = r.TryLength,
        HandlerOffset = r.HandlerOffset,
        HandlerLength = r.HandlerLength,
        CatchType = r.CatchType,
    };
}

/// <summary>一个已解析的方法体：IL 字节、局部变量签名、异常表、以及切好的指令序列。</summary>
internal sealed class IlMethodBody
{
    public required byte[] Il { get; init; }
    public required IReadOnlyList<IlInstruction> Instructions { get; init; }
    public int MaxStack { get; init; }
    public bool InitLocals { get; init; }
    public System.Reflection.Metadata.StandaloneSignatureHandle LocalSignature { get; init; }
    public required IReadOnlyList<IlException> Exceptions { get; init; }

    /// <summary>按绝对偏移找指令下标；找到返回下标，否则 -1。</summary>
    public int IndexOf(int offset)
    {
        for (int i = 0; i < Instructions.Count; i++)
        {
            if (Instructions[i].Offset == offset)
            {
                return i;
            }
        }
        return -1;
    }

    /// <summary>用新的 IL 字节换掉方法体，指令序列自动重新解析（异常表由调用方按映射修正后传入）。</summary>
    public IlMethodBody With(byte[] il, int maxStack, IReadOnlyList<IlException> exceptions) =>
        With(il, maxStack, exceptions, LocalSignature);

    /// <summary>同上，但换用新的局部变量签名（控制流平坦化要追加一个 state 局部）。</summary>
    public IlMethodBody With(byte[] il, int maxStack, IReadOnlyList<IlException> exceptions,
        System.Reflection.Metadata.StandaloneSignatureHandle localSignature)
    {
        IlMethodBody parsed = IlReader.Read(il, maxStack, InitLocals, localSignature, []);
        return new IlMethodBody
        {
            Il = il,
            Instructions = parsed.Instructions,
            MaxStack = maxStack,
            InitLocals = InitLocals,
            LocalSignature = localSignature,
            Exceptions = exceptions,
        };
    }
}

/// <summary>IL 读取器：把方法体字节摊成指令列表，分支目标换算为绝对偏移。</summary>
internal static class IlReader
{
    public static IlMethodBody Read(byte[] il, int maxStack, bool initLocals,
        System.Reflection.Metadata.StandaloneSignatureHandle localSig,
        IReadOnlyList<System.Reflection.Metadata.ExceptionRegion> exceptions)
    {
        var list = new List<IlInstruction>();
        int p = 0;
        while (p < il.Length)
        {
            int at = p;
            int opcode = il[p++];
            if (opcode == 0xFE)
            {
                if (p >= il.Length)
                {
                    throw new BadImageFormatException($"偏移 {at}：0xFE 后缺少次操作码");
                }
                opcode = 0xFE00 | il[p++];
            }
            ushort code = (ushort)opcode;
            if (!IlOpcodes.TryRead(code, out OperandType kind))
            {
                throw new BadImageFormatException($"偏移 {at}：未知 IL 操作码 0x{code:X4}");
            }

            object? operand = null;
            switch (kind)
            {
                case OperandType.InlineNone:
                    break;
                case OperandType.ShortInlineI:
                    operand = (sbyte)il[p++];
                    break;
                case OperandType.InlineI:
                    operand = BitConverter.ToInt32(il, p);
                    p += 4;
                    break;
                case OperandType.InlineI8:
                    operand = BitConverter.ToInt64(il, p);
                    p += 8;
                    break;
                case OperandType.ShortInlineR:
                    operand = BitConverter.ToSingle(il, p);
                    p += 4;
                    break;
                case OperandType.InlineR:
                    operand = BitConverter.ToDouble(il, p);
                    p += 8;
                    break;
                case OperandType.ShortInlineVar:
                    operand = (int)il[p++];
                    break;
                case OperandType.InlineVar:
                    operand = (int)BitConverter.ToUInt16(il, p);
                    p += 2;
                    break;
                case OperandType.InlineField:
                case OperandType.InlineMethod:
                case OperandType.InlineSig:
                case OperandType.InlineString:
                case OperandType.InlineTok:
                case OperandType.InlineType:
                    operand = BitConverter.ToInt32(il, p);
                    p += 4;
                    break;
                case OperandType.ShortInlineBrTarget:
                {
                    int delta = (sbyte)il[p++];
                    operand = p + delta;
                    break;
                }
                case OperandType.InlineBrTarget:
                {
                    int delta = BitConverter.ToInt32(il, p);
                    p += 4;
                    operand = p + delta;
                    break;
                }
                case OperandType.InlineSwitch:
                {
                    int n = BitConverter.ToInt32(il, p);
                    p += 4;
                    int baseOffset = p + 4 * n;
                    var targets = new int[n];
                    for (int i = 0; i < n; i++)
                    {
                        targets[i] = baseOffset + BitConverter.ToInt32(il, p);
                        p += 4;
                    }
                    operand = targets;
                    break;
                }
                default:
                    throw new BadImageFormatException($"偏移 {at}：不支持的操作数类型 {kind}");
            }
            list.Add(new IlInstruction { OpCode = code, Operand = operand, Offset = at });
        }
        return new IlMethodBody
        {
            Il = il,
            Instructions = list,
            MaxStack = maxStack,
            InitLocals = initLocals,
            LocalSignature = localSig,
            Exceptions = exceptions.Select(IlException.From).ToArray(),
        };
    }
}

/// <summary>IL 生成器：标签式跳转 + 恒定长格式，一次遍历定偏移，无需收敛迭代。</summary>
internal sealed class IlBuilder
{
    private readonly List<object> _items = [];

    public int Count => _items.Count;

    public IlLabel NewLabel() => new();

    public void Mark(IlLabel label)
    {
        if (label.Offset >= 0)
        {
            throw new InvalidOperationException("标签已标记过");
        }
        _items.Add(label);
    }

    public void Emit(ushort opcode, object? operand = null) =>
        _items.Add(new IlInstruction { OpCode = opcode, Operand = operand });

    /// <summary>按名字发指令（见 <see cref="IlOpcodes.Code"/>），避免在注入代码里散落魔数。</summary>
    public void Emit(string opcode, object? operand = null) => Emit(IlOpcodes.Code(opcode), operand);

    /// <summary>按标签回填分支相对偏移，输出方法体字节。长度恒定，一轮遍历即可定偏移。</summary>
    public byte[] Build()
    {
        // 第一遍：算出每个标签的落点与每条指令的偏移（长度恒定，一遍即可）。
        int offset = 0;
        foreach (object item in _items)
        {
            if (item is IlLabel label)
            {
                label.Offset = offset;
                continue;
            }
            var ins = (IlInstruction)item;
            ins.Offset = offset;
            offset += IlOpcodes.CanonicalSize(ins.OpCode, ins.Operand);
        }

        // 第二遍：写字节，分支相对偏移此刻已能算准。
        var outBytes = new List<byte>(offset);
        foreach (object item in _items)
        {
            if (item is not IlInstruction ins)
            {
                continue;
            }
            Write(outBytes, ins);
        }
        return outBytes.ToArray();
    }

    private static void Write(List<byte> outBytes, IlInstruction ins)
    {
        if (!IlOpcodes.TryWrite(ins.OpCode, out ushort canonical, out OperandType kind))
        {
            throw new InvalidOperationException($"未知 IL 操作码 0x{ins.OpCode:X4}");
        }
        if (canonical > 0xFF)
        {
            outBytes.Add(0xFE);
            outBytes.Add((byte)(canonical & 0xFF));
        }
        else
        {
            outBytes.Add((byte)canonical);
        }

        int next = ins.Offset + IlOpcodes.CanonicalSize(ins.OpCode, ins.Operand);
        switch (kind)
        {
            case OperandType.InlineNone:
                break;
            case OperandType.ShortInlineI:
                outBytes.Add(unchecked((byte)Convert.ToInt32(ins.Operand)));
                break;
            case OperandType.InlineI:
                AddI4(outBytes, Convert.ToInt32(ins.Operand));
                break;
            case OperandType.InlineI8:
                AddI8(outBytes, Convert.ToInt64(ins.Operand));
                break;
            case OperandType.ShortInlineR:
                AddI4(outBytes, BitConverter.SingleToInt32Bits(Convert.ToSingle(ins.Operand)));
                break;
            case OperandType.InlineR:
                AddI8(outBytes, BitConverter.DoubleToInt64Bits(Convert.ToDouble(ins.Operand)));
                break;
            case OperandType.ShortInlineVar:
                outBytes.Add(unchecked((byte)Convert.ToInt32(ins.Operand)));
                break;
            case OperandType.InlineVar:
                outBytes.Add(unchecked((byte)Convert.ToInt32(ins.Operand)));
                outBytes.Add(unchecked((byte)(Convert.ToInt32(ins.Operand) >> 8)));
                break;
            case OperandType.InlineField:
            case OperandType.InlineMethod:
            case OperandType.InlineSig:
            case OperandType.InlineString:
            case OperandType.InlineTok:
            case OperandType.InlineType:
                AddI4(outBytes, Convert.ToInt32(ins.Operand));
                break;
            case OperandType.ShortInlineBrTarget:
                outBytes.Add(unchecked((byte)(Label(ins).Offset - next)));
                break;
            case OperandType.InlineBrTarget:
                AddI4(outBytes, Label(ins).Offset - next);
                break;
            case OperandType.InlineSwitch:
            {
                var labels = (IlLabel[])ins.Operand!;
                AddI4(outBytes, labels.Length);
                // switch 的目标偏移相对整条指令之后（含目标表）计算，也就是 next。
                foreach (IlLabel l in labels)
                {
                    AddI4(outBytes, l.Offset - next);
                }
                break;
            }
            default:
                throw new InvalidOperationException($"不支持写出的操作数类型 {kind}");
        }
    }

    private static IlLabel Label(IlInstruction ins) =>
        ins.Operand as IlLabel ?? throw new InvalidOperationException($"指令 0x{ins.OpCode:X4} 的分支目标不是标签");

    private static void AddI4(List<byte> to, int v)
    {
        to.Add((byte)v);
        to.Add((byte)(v >> 8));
        to.Add((byte)(v >> 16));
        to.Add((byte)(v >> 24));
    }

    private static void AddI8(List<byte> to, long v)
    {
        AddI4(to, (int)v);
        AddI4(to, (int)(v >> 32));
    }
}

/// <summary>
/// 把解析出来的指令序列重新汇编成方法体字节，并给出「旧偏移 -> 新偏移」映射。
///
/// <para>分支目标在读取阶段是绝对偏移，汇编时换成标签；异常表边界不是指令、不会被分支引用，
/// 但长度一变同样会失效，所以也要一并登记标签，最后按映射修正。</para>
/// </summary>
internal static class IlAssembler
{
    public static (byte[] Il, Dictionary<int, int> OffsetMap) Assemble(
        IReadOnlyList<IlInstruction> instructions,
        IEnumerable<int>? extraOffsets = null)
    {
        var builder = new IlBuilder();
        var labels = new Dictionary<int, IlLabel>();

        IlLabel LabelAt(int offset)
        {
            if (!labels.TryGetValue(offset, out IlLabel? label))
            {
                label = builder.NewLabel();
                labels[offset] = label;
            }
            return label;
        }

        foreach (IlInstruction ins in instructions)
        {
            if (ins.IsBranch)
            {
                LabelAt((int)ins.Operand!);
            }
            else if (ins.IsSwitch)
            {
                foreach (int t in (int[])ins.Operand!)
                {
                    LabelAt(t);
                }
            }
        }
        if (extraOffsets is not null)
        {
            foreach (int off in extraOffsets)
            {
                LabelAt(off);
            }
        }

        foreach (IlInstruction ins in instructions)
        {
            if (labels.TryGetValue(ins.Offset, out IlLabel? at))
            {
                builder.Mark(at);
            }
            builder.Emit(ins.OpCode, Operand(ins, labels));
        }
        if (instructions.Count > 0)
        {
            IlInstruction last = instructions[^1];
            int oldEnd = last.Offset + IlOpcodes.SizeOf(last.OpCode, last.Operand);
            if (labels.TryGetValue(oldEnd, out IlLabel? tail))
            {
                builder.Mark(tail);
            }
        }

        byte[] il = builder.Build();
        var map = new Dictionary<int, int>(labels.Count);
        foreach (KeyValuePair<int, IlLabel> e in labels)
        {
            map[e.Key] = e.Value.Offset;
        }
        return (il, map);
    }

    /// <summary>异常表边界集合：起点与终点（起点+长度）都要跟着偏移一起修正。</summary>
    public static IEnumerable<int> Boundaries(IReadOnlyList<IlException> regions)
    {
        foreach (IlException r in regions)
        {
            yield return r.TryOffset;
            yield return r.TryOffset + r.TryLength;
            yield return r.HandlerOffset;
            yield return r.HandlerOffset + r.HandlerLength;
        }
    }

    /// <summary>按偏移映射修正整张异常表。边界必须都登记过映射，缺一个说明变换漏了边界。</summary>
    public static IlException[] Remap(IReadOnlyList<IlException> regions, IReadOnlyDictionary<int, int> map)
    {
        var list = new IlException[regions.Count];
        for (int i = 0; i < regions.Count; i++)
        {
            IlException r = regions[i];
            int tryStart = map[r.TryOffset];
            int tryEnd = map[r.TryOffset + r.TryLength];
            int handlerStart = map[r.HandlerOffset];
            int handlerEnd = map[r.HandlerOffset + r.HandlerLength];
            list[i] = new IlException
            {
                Kind = r.Kind,
                TryOffset = tryStart,
                TryLength = tryEnd - tryStart,
                HandlerOffset = handlerStart,
                HandlerLength = handlerEnd - handlerStart,
                CatchType = r.CatchType,
            };
        }
        return list;
    }

    private static object? Operand(IlInstruction ins, Dictionary<int, IlLabel> labels)
    {
        if (ins.IsBranch)
        {
            return labels[(int)ins.Operand!];
        }
        if (ins.IsSwitch)
        {
            int[] targets = (int[])ins.Operand!;
            var arr = new IlLabel[targets.Length];
            for (int i = 0; i < targets.Length; i++)
            {
                arr[i] = labels[targets[i]];
            }
            return arr;
        }
        return ins.Operand;
    }
}