using System.Reflection.Emit;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;

namespace PaccManager.Pco;

/// <summary>
/// IL 级控制流平坦化：把方法体切成本基本块，再摊成一个 <c>switch(state)</c> 状态机，
/// 基本块之间的直接跳转改成「写 state + 回跳分发器」。反编译出来的 CFG 被抹平，
/// 人读到的是一张没有结构的跳转表。
///
/// <para><b>保守优先。</b>平坦化一旦判断错就会生成非法 IL，产物直接加载失败，代价远大于收益。
/// 所以凡是拿不准的方法一律原样放行：带异常区间的（不跨越 try/catch 边界）、带 switch 的、
/// 本机调用 / 栈上动态寻址的、基本块入口栈非空的、存在不可达基本块的、太小的，全部跳过。</para>
///
/// <para><b>栈深分析是必需的。</b>状态机要求每个基本块「从空栈开始」，而 C# 里三元表达式
/// 这类构造会让两个分支push 同一个值、在汇合块入口留下非空栈。那种方法无法用状态机表达，
/// 必须靠栈深分析识别出来并跳过。指令的栈效应取自 <see cref="OpCode.StackBehaviourPop"/>，
/// call 族（变长）另按被调方法的签名算参数个数。</para>
/// </summary>
internal static class ControlFlowFlattener
{
    /// <summary>指令太少的方法平坦化只会变大变慢，没有收益。</summary>
    private const int MinInstructions = 8;

    /// <summary>状态数上限，防止 switch 跳转表失控。</summary>
    private const int MaxBlocks = 256;

    /// <summary>
    /// 不参与平坦化的操作码：jmp 与 calli 改变返回/调用边界，localloc 在栈上动态寻址，
    /// leave/endfinally/endfilter/rethrow 都绑定异常区间，switch 的跳转表会和状态机的
    /// switch 缠在一起、失去混淆意义。arglist 同属变长且少见的构造。
    /// </summary>
    private static readonly HashSet<ushort> Rejected =
    [
        0x27,   // jmp
        0x29,   // calli
        0x45,   // switch
        0xDC,   // endfinally
        0xDD, 0xDE, // leave / leave.s
        0xFE00, // arglist
        0xFE0F, // localloc
        0xFE11, // endfilter
        0xFE1A, // rethrow
    ];

    public static IlMethodBody? Flatten(AssemblyRewriter rewriter, IlMethodBody model)
    {
        // 异常区间不与状态机共存：设计文档风险表要求保留 try/catch 的完整性。
        if (model.Exceptions.Count > 0 || model.Instructions.Count < MinInstructions)
        {
            return null;
        }
        foreach (IlInstruction ins in model.Instructions)
        {
            if (Rejected.Contains(ins.OpCode))
            {
                return null;
            }
        }

        MetadataReader md = rewriter.Metadata;
        if (!TryBuildLayout(model, out Layout? layout))
        {
            return null;
        }
        if (layout.Blocks.Count < 2 || layout.Blocks.Count > MaxBlocks)
        {
            return null;
        }
        if (!TryAnalyzeStack(md, model, layout, out int maxDepth))
        {
            return null;
        }

        // 追加一个 int32 局部变量当状态寄存器；既有局部变量的编号不变。
        byte[]? locals = AppendInt32Local(md, model, out int stateLocal);
        if (locals is null)
        {
            return null;
        }
        int localRow = rewriter.ReserveLocalSignature(locals);

        var il = new IlBuilder();
        var cases = new IlLabel[layout.Blocks.Count];
        for (int i = 0; i < cases.Length; i++)
        {
            cases[i] = il.NewLabel();
        }
        IlLabel loop = il.NewLabel();

        // state = 0; while (true) switch (state) { case 0..N }
        il.Emit("ldc.i4", 0);
        il.Emit("stloc", stateLocal);
        il.Emit("br", loop);
        il.Mark(loop);
        il.Emit("ldloc", stateLocal);
        // switch 越界会落到紧随其后的指令，也就是 case 0 的入口——非法状态回退到起点，无害。
        il.Emit("switch", cases);

        for (int i = 0; i < layout.Blocks.Count; i++)
        {
            il.Mark(cases[i]);
            EmitBlock(il, model, layout, i, stateLocal, loop);
        }

        byte[] bytes = il.Build();
        int maxStack = Math.Max(model.MaxStack, maxDepth + 2);
        return model.With(bytes, maxStack, [], MetadataTokens.StandaloneSignatureHandle(localRow));
    }

    // ------------------------------------------------------------------
    // 基本块
    // ------------------------------------------------------------------

    private sealed class BasicBlock
    {
        public int Start;       // 指令下标（含）
        public int End;         // 指令下标（不含）
        public int EntryStack = -1;
    }

    private sealed class Layout
    {
        public required List<BasicBlock> Blocks { get; init; }
        public required Dictionary<int, int> IndexOfOffset { get; init; }
        public required int[] BlockOfInstruction { get; init; }
    }

    /// <summary>按「分支/switch/ret/throw + 分支目标」切基本块。</summary>
    private static bool TryBuildLayout(IlMethodBody model, out Layout layout)
    {
        layout = null!;
        IReadOnlyList<IlInstruction> ins = model.Instructions;
        var indexOfOffset = new Dictionary<int, int>(ins.Count);
        for (int i = 0; i < ins.Count; i++)
        {
            indexOfOffset[ins[i].Offset] = i;
        }

        var leaders = new SortedSet<int> { 0 };
        for (int i = 0; i < ins.Count; i++)
        {
            IlInstruction cur = ins[i];
            if (cur.IsBranch)
            {
                if (!indexOfOffset.ContainsKey(cur.BranchTarget))
                {
                    return false; // 跳到方法体外：不合法，放弃
                }
                leaders.Add(indexOfOffset[cur.BranchTarget]);
                if (i + 1 < ins.Count)
                {
                    leaders.Add(i + 1);
                }
            }
            else if (IsTerminator(cur) && i + 1 < ins.Count)
            {
                leaders.Add(i + 1);
            }
        }

        int[] starts = leaders.ToArray();
        var blocks = new List<BasicBlock>(starts.Length);
        var blockOf = new int[ins.Count];
        for (int i = 0; i < starts.Length; i++)
        {
            int end = i + 1 < starts.Length ? starts[i + 1] : ins.Count;
            blocks.Add(new BasicBlock { Start = starts[i], End = end });
            for (int j = starts[i]; j < end; j++)
            {
                blockOf[j] = i;
            }
        }

        layout = new Layout { Blocks = blocks, IndexOfOffset = indexOfOffset, BlockOfInstruction = blockOf };
        return true;
    }

    /// <summary>终结指令：分支、ret、throw。它们之后不会顺序执行。</summary>
    private static bool IsTerminator(IlInstruction ins) =>
        ins.IsBranch || ins.OpCode is 0x2A or 0x7A;

    // ------------------------------------------------------------------
    // 栈深分析
    // ------------------------------------------------------------------

    /// <summary>
    /// 从入口块做前向传播，校验每个基本块入口栈深一致且可达、过程不出现下溢。
    /// 任一条不满足就返回 false（整个方法跳过）。
    /// </summary>
    private static bool TryAnalyzeStack(MetadataReader md, IlMethodBody model, Layout layout,
        out int maxDepth)
    {
        maxDepth = 0;
        IReadOnlyList<IlInstruction> ins = model.Instructions;
        layout.Blocks[0].EntryStack = 0;
        var queue = new Queue<int>();
        queue.Enqueue(0);

        while (queue.Count > 0)
        {
            int bi = queue.Dequeue();
            BasicBlock block = layout.Blocks[bi];
            int depth = block.EntryStack;

            for (int i = block.Start; i < block.End; i++)
            {
                IlInstruction cur = ins[i];
                if (cur.OpCode is 0x2A or 0x7A)
                {
                    break; // ret/throw 的栈效应不影响后继，且签名不定长，不必算
                }
                if (!TryStackDelta(md, cur, out int pop, out int push))
                {
                    return false;
                }
                depth -= pop;
                if (depth < 0)
                {
                    return false;
                }
                depth += push;
                maxDepth = Math.Max(maxDepth, depth);
            }

            List<int>? next = Successors(ins[block.End - 1], bi, layout);
            if (next is null)
            {
                return false;
            }
            foreach (int s in next)
            {
                if (layout.Blocks[s].EntryStack < 0)
                {
                    layout.Blocks[s].EntryStack = depth;
                    queue.Enqueue(s);
                }
                else if (layout.Blocks[s].EntryStack != depth)
                {
                    // 同一基本块有多条入口且栈深不同：合法 CIL 不会这样，状态机也表达不了
                    return false;
                }
            }
        }

        foreach (BasicBlock b in layout.Blocks)
        {
            if (b.EntryStack < 0)
            {
                return false; // 不可达基本块：栈深无从谈起
            }
        }
        return true;
    }

    /// <summary>基本块的后继块下标；返回 null 表示控制流不合法。</summary>
    private static List<int>? Successors(IlInstruction last, int blockIndex, Layout layout)
    {
        var result = new List<int>(2);
        if (last.IsBranch)
        {
            result.Add(layout.BlockOfInstruction[layout.IndexOfOffset[last.BranchTarget]]);
            if (IsUnconditional(last.OpCode))
            {
                return result;
            }
            if (blockIndex + 1 >= layout.Blocks.Count)
            {
                return null; // 条件分支的 fallthrough 落到了方法体外
            }
            result.Add(blockIndex + 1);
            return result;
        }
        if (IsTerminator(last))
        {
            return result; // ret / throw 没有后继
        }
        if (blockIndex + 1 >= layout.Blocks.Count)
        {
            return null; // 方法体没有终结指令就结束了
        }
        result.Add(blockIndex + 1);
        return result;
    }

    private static bool IsUnconditional(ushort code) =>
        IlOpcodes.TryWrite(code, out ushort canonical, out _) && canonical == 0x38; // br

    private static bool TryStackDelta(MetadataReader md, IlInstruction ins, out int pop, out int push)
    {
        if (ins.OpCode is 0x28 or 0x6F)
        {
            return TryCallEffect(md, (int)ins.Operand!, newObj: false, out pop, out push);
        }
        if (ins.OpCode == 0x73)
        {
            return TryCallEffect(md, (int)ins.Operand!, newObj: true, out pop, out push);
        }
        OpCode op = IlOpcodes.OpCodeOf(ins.OpCode);
        pop = PopCount(op.StackBehaviourPop);
        push = PushCount(op.StackBehaviourPush);
        return pop >= 0 && push >= 0;
    }

    private static int PopCount(StackBehaviour b) => b switch
    {
        StackBehaviour.Pop0 => 0,
        StackBehaviour.Pop1 or StackBehaviour.Popi or StackBehaviour.Popref => 1,
        StackBehaviour.Pop1_pop1 or StackBehaviour.Popi_pop1 or StackBehaviour.Popi_popi
            or StackBehaviour.Popi_popi8 or StackBehaviour.Popi_popr4 or StackBehaviour.Popi_popr8
            or StackBehaviour.Popref_pop1 or StackBehaviour.Popref_popi => 2,
        StackBehaviour.Popi_popi_popi or StackBehaviour.Popref_popi_popi or StackBehaviour.Popref_popi_popi8
            or StackBehaviour.Popref_popi_popr4 or StackBehaviour.Popref_popi_popr8
            or StackBehaviour.Popref_popi_popref => 3,
        _ => -1, // Varpop
    };

    private static int PushCount(StackBehaviour b) => b switch
    {
        StackBehaviour.Push0 => 0,
        StackBehaviour.Push1 or StackBehaviour.Push1_push1 or StackBehaviour.Pushi
            or StackBehaviour.Pushi8 or StackBehaviour.Pushr4 or StackBehaviour.Pushr8
            or StackBehaviour.Pushref => 1,
        _ => -1, // Varpush
    };

    /// <summary>call/callvirt/newobj 的栈效应：按签名算参数个数，返回类型非 void 则压一个值。</summary>
    private static bool TryCallEffect(MetadataReader md, int token, bool newObj, out int pop, out int push)
    {
        pop = 0;
        push = 0;
        byte[]? blob = MethodSignatureBlob(md, token);
        if (blob is null || blob.Length < 3)
        {
            return false;
        }

        int pos = 0;
        byte callingConvention = blob[pos++];
        if ((callingConvention & 0x10) != 0) // GENERIC：先跳过泛型形参个数
        {
            if (!TryReadCompressed(blob, ref pos, out _))
            {
                return false;
            }
        }
        if (!TryReadCompressed(blob, ref pos, out uint paramCount) || pos >= blob.Length)
        {
            return false;
        }

        bool isVoid = blob[pos] == 0x01; // ELEMENT_TYPE_VOID
        bool hasThis = (callingConvention & 0x20) != 0;
        pop = (int)paramCount + (hasThis && !newObj ? 1 : 0);
        push = newObj ? 1 : (isVoid ? 0 : 1);
        return true;
    }

    /// <summary>取被调方法的 MethodDefSig / MethodRefSig 原始 blob。</summary>
    private static byte[]? MethodSignatureBlob(MetadataReader md, int token)
    {
        int row = token & 0x00FFFFFF;
        if (row == 0)
        {
            return null;
        }
        switch (token >>> 24)
        {
            case 0x06: // MethodDef
                // 注入方法的行号在原元数据之外（反调试/完整性/代理都在平坦化之前注入），
                // 查不到就放弃，别让一次越界读把整份产物带崩。
                return row <= md.MethodDefinitions.Count
                    ? md.GetBlobBytes(
                        md.GetMethodDefinition(MetadataTokens.MethodDefinitionHandle(row)).Signature)
                    : null;
            case 0x0A: // MemberRef
            {
                if (row > md.MemberReferences.Count)
                {
                    return null;
                }
                MemberReference mr = md.GetMemberReference(MetadataTokens.MemberReferenceHandle(row));
                return mr.GetKind() == MemberReferenceKind.Method ? md.GetBlobBytes(mr.Signature) : null;
            }
            case 0x2B: // MethodSpec：拿底层方法定义/引用的签名，泛型实例化不改参数个数
                if (row > md.GetTableRowCount(TableIndex.MethodSpec))
                {
                    return null;
                }
                EntityHandle method = md.GetMethodSpecification(
                    MetadataTokens.MethodSpecificationHandle(row)).Method;
                return method.Kind switch
                {
                    HandleKind.MethodDefinition when MetadataTokens.GetRowNumber(method) <= md.MethodDefinitions.Count =>
                        md.GetBlobBytes(md.GetMethodDefinition((MethodDefinitionHandle)method).Signature),
                    HandleKind.MemberReference when MetadataTokens.GetRowNumber(method) <= md.MemberReferences.Count =>
                        md.GetBlobBytes(md.GetMemberReference((MemberReferenceHandle)method).Signature),
                    _ => null,
                };
            default:
                return null;
        }
    }

    // ------------------------------------------------------------------
    // 状态机输出
    // ------------------------------------------------------------------

    private static void EmitBlock(IlBuilder il, IlMethodBody model, Layout layout, int blockIndex,
        int stateLocal, IlLabel loop)
    {
        BasicBlock block = layout.Blocks[blockIndex];
        IReadOnlyList<IlInstruction> ins = model.Instructions;

        for (int i = block.Start; i < block.End; i++)
        {
            IlInstruction cur = ins[i];
            if (!IsTerminator(cur))
            {
                il.Emit(cur.OpCode, cur.Operand);
                continue;
            }

            // ret / throw：终结执行，原样保留。
            if (cur.OpCode is 0x2A or 0x7A)
            {
                il.Emit(cur.OpCode, cur.Operand);
                return;
            }

            int target = layout.BlockOfInstruction[layout.IndexOfOffset[cur.BranchTarget]];
            if (IsUnconditional(cur.OpCode))
            {
                SetState(il, stateLocal, loop, target);
                return;
            }

            // 条件分支：条件为真走原目标，为假走 fallthrough，两边都写 state 再回跳。
            int fallthrough = blockIndex + 1;
            IlLabel taken = il.NewLabel();
            il.Emit(cur.OpCode, taken);
            SetState(il, stateLocal, loop, fallthrough);
            il.Mark(taken);
            SetState(il, stateLocal, loop, target);
            return;
        }

        // 基本块没有终结指令：顺序落到下一块。
        SetState(il, stateLocal, loop, blockIndex + 1);
    }

    private static void SetState(IlBuilder il, int stateLocal, IlLabel loop, int state)
    {
        il.Emit("ldc.i4", state);
        il.Emit("stloc", stateLocal);
        il.Emit("br", loop);
    }

    // ------------------------------------------------------------------
    // 局部变量签名
    // ------------------------------------------------------------------

    /// <summary>在既有局部变量签名末尾追加一个 int32，返回新 blob；签名结构异常时返回 null。</summary>
    private static byte[]? AppendInt32Local(MetadataReader md, IlMethodBody model, out int stateLocal)
    {
        stateLocal = 0;
        if (model.LocalSignature.IsNil)
        {
            return [0x07, 0x01, 0x08];
        }
        byte[] original = md.GetBlobBytes(md.GetStandaloneSignature(model.LocalSignature).Signature);
        if (original.Length == 0 || original[0] != 0x07)
        {
            return null;
        }
        int pos = 1;
        if (!TryReadCompressed(original, ref pos, out uint count))
        {
            return null;
        }
        stateLocal = (int)count;

        var blob = new List<byte>(original.Length + 2) { 0x07 };
        WriteCompressed(blob, count + 1);
        for (int i = pos; i < original.Length; i++)
        {
            blob.Add(original[i]);
        }
        blob.Add(0x08); // ELEMENT_TYPE_I4
        return blob.ToArray();
    }

    /// <summary>ECMA-335 压缩无符号整数。</summary>
    private static bool TryReadCompressed(byte[] blob, ref int pos, out uint value)
    {
        value = 0;
        if (pos >= blob.Length)
        {
            return false;
        }
        byte b = blob[pos++];
        if ((b & 0x80) == 0)
        {
            value = b;
            return true;
        }
        if ((b & 0xC0) == 0x80)
        {
            if (pos >= blob.Length)
            {
                return false;
            }
            value = (uint)(((b & 0x3F) << 8) | blob[pos++]);
            return true;
        }
        if ((b & 0xE0) == 0xC0)
        {
            if (pos + 2 >= blob.Length)
            {
                return false;
            }
            value = ((uint)(b & 0x1F) << 24) | ((uint)blob[pos++] << 16) | ((uint)blob[pos++] << 8);
            value |= blob[pos++];
            return true;
        }
        return false;
    }

    private static void WriteCompressed(List<byte> to, uint value)
    {
        if (value <= 0x7F)
        {
            to.Add((byte)value);
        }
        else if (value <= 0x3FFF)
        {
            to.Add((byte)(0x80 | (value >> 8)));
            to.Add((byte)value);
        }
        else
        {
            to.Add((byte)(0xC0 | (value >> 24)));
            to.Add((byte)(value >> 16));
            to.Add((byte)(value >> 8));
            to.Add((byte)value);
        }
    }
}