using System.Buffers.Binary;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;

namespace PaccManager.Pco;

/// <summary>
/// 引用代理：把方法体里的直接调用 <c>call/callvirt &lt;目标&gt;</c> 换成
/// <c>call __Proxy::mN</c>，代理方法内部再转发到真正的目标。反编译看到的调用图里，
/// 业务方法不再直接指向 API，而是指向一堆同构的 trampoline，调用关系被抹平。
///
/// <para><b>为什么用「同签名静态转发方法」而不是 <c>calli</c>：</b><c>calli</c> 需要给每个签名
/// 建 StandaloneSig，且控制流平坦化会一律跳过含 <c>calli</c> 的方法，等于把平坦化废掉。
/// 转发方法只是普通 <c>call</c>，两种变换可以叠加。</para>
///
/// <para><b>不代理的几类目标：</b>值类型（实例方法在 IL 里 <c>this</c> 是托管指针 <c>&amp;T</c>，
/// 代理参数按声明类型传值会对不上，JIT 直接拒绝整个程序集）、泛型方法、签名里含泛型参数
/// （<c>!0</c>/<c>!!0</c>）的方法、<c>constrained.</c> 前缀、以及同程序集里 private / protected 系的成员
/// ——<c>__Proxy</c> 是不相关的兄弟类型，够不着它们，硬代理会在运行期抛 <c>MethodAccessException</c>。</para>
///
/// <para><b>替换只是把调用点换掉，指令长度不变：</b>操作码写回 <c>call</c>(0x28)（<c>callvirt</c>
/// 换成 <c>call</c> 是为了走代理的静态签名），后面 4 字节换代理令牌。长度一致，所以方法体偏移、
/// 异常表边界全都不用动，直接原地改字节。</para>
/// </summary>
internal sealed class ProxyGenerator
{
    private readonly AssemblyRewriter _rewriter;
    private readonly MetadataReader _md;
    private readonly PendingType _type;
    private readonly int _methodBase;
    private readonly int _objectRef;

    /// <summary>
    /// 「原目标令牌 + 调用方式」-> 代理方法令牌。同一目标的 call 与 callvirt 必须分开建代理：
    /// 前者是非虚调用（base 调用靠它才不会被打回覆写），后者保留虚分派，合并会把 base.X() 变成死循环。
    /// </summary>
    private readonly Dictionary<(int Target, bool IsCallvirt), int> _proxies = [];

    /// <summary>被我方排除的值类型（接收者不能按声明类型传值）。只覆盖核心库常见的那批。</summary>
    private static readonly HashSet<string> CoreValueTypes = new(StringComparer.Ordinal)
    {
        "Boolean", "Byte", "SByte", "Char", "Int16", "UInt16", "Int32", "UInt32", "Int64", "UInt64",
        "Single", "Double", "Decimal", "DateTime", "TimeSpan", "Guid", "IntPtr", "UIntPtr", "Half",
        "DateOnly", "TimeOnly", "Void", "RuntimeTypeHandle", "RuntimeMethodHandle", "RuntimeFieldHandle",
    };

    /// <summary>统计：被改写成间接调用的调用点个数。</summary>
    public int ProxiedCalls { get; private set; }

    private ProxyGenerator(AssemblyRewriter rewriter, PendingType type, int methodBase, int objectRef)
    {
        _rewriter = rewriter;
        _md = rewriter.Metadata;
        _type = type;
        _methodBase = methodBase;
        _objectRef = objectRef;
    }

    /// <summary>登记 __Proxy 类型。必须在其它注入类型之后调用，方法令牌基址才接得上。</summary>
    public static ProxyGenerator Create(AssemblyRewriter rewriter)
    {
        int objectRef = rewriter.RequireTypeRef("System", "Object");
        int methodBase = rewriter.InjectedMethodCount;
        var type = new PendingType
        {
            Namespace = "PaccManager",
            Name = "__Proxy",
            Attributes = TypeAttributes.Public | TypeAttributes.Sealed | TypeAttributes.Abstract
                | TypeAttributes.BeforeFieldInit,
            BaseTypeToken = objectRef,
        };
        rewriter.AddInjectedType(type);
        return new ProxyGenerator(rewriter, type, methodBase, objectRef);
    }

    /// <summary>扫描方法体，把可代理的直接调用换成代理调用。没有可改的返回 null。</summary>
    public IlMethodBody? Rewrite(MethodDefinitionHandle handle, IlMethodBody model)
    {
        _ = handle;
        byte[]? patched = null;
        for (int i = 0; i < model.Instructions.Count; i++)
        {
            IlInstruction ins = model.Instructions[i];
            if (ins.OpCode != 0x28 && ins.OpCode != 0x6F)
            {
                continue;
            }
            if (ins.Operand is not int target)
            {
                continue;
            }
            // constrained. 前缀下接收者可能是值类型的托管指针，整条跳过。
            if (i > 0 && model.Instructions[i - 1].OpCode == 0xFE16)
            {
                continue;
            }
            if (!TryProxy(target, ins.OpCode == 0x6F, out int proxyToken))
            {
                continue;
            }
            patched ??= (byte[])model.Il.Clone();
            patched[ins.Offset] = 0x28;
            BinaryPrimitives.WriteInt32LittleEndian(patched.AsSpan(ins.Offset + 1, 4), proxyToken);
            ProxiedCalls++;
        }
        return patched is null ? null : model.With(patched, model.MaxStack, model.Exceptions);
    }

    // ------------------------------------------------------------------
    // 目标判定
    // ------------------------------------------------------------------

    private bool TryProxy(int target, bool isCallvirt, out int proxyToken)
    {
        if (_proxies.TryGetValue((target, isCallvirt), out proxyToken))
        {
            return true;
        }

        if (!TryDescribe(target, isCallvirt, out string name, out byte[] signature,
                out bool hasThis, out int ownerToken, out int paramCount))
        {
            return false;
        }
        if (name.Length == 0 || name[0] == '.')
        {
            return false; // .ctor / .cctor 及编译器保留名
        }
        if (hasThis && ownerToken == 0)
        {
            return false;
        }
        if (!TryBuildSignature(signature, hasThis, ownerToken, out byte[] proxySignature))
        {
            return false;
        }

        int index = _type.Methods.Count;
        var method = new PendingMethod
        {
            Name = "m" + index,
            Attributes = MethodAttributes.Public | MethodAttributes.Static | MethodAttributes.HideBySig,
            Signature = proxySignature,
            Il = BuildForwarder(paramCount, hasThis, isCallvirt, target),
            MaxStack = Math.Max(1, paramCount + (hasThis ? 1 : 0)),
        };
        _type.Methods.Add(method);
        proxyToken = _rewriter.InjectedMethodToken(_methodBase + index);
        _proxies[(target, isCallvirt)] = proxyToken;
        return true;
    }

    /// <summary>解析调用目标，判断能不能安全地建代理，并取出转发所需的签名信息。</summary>
    private bool TryDescribe(int token, bool isCallvirt, out string name, out byte[] signature,
        out bool hasThis, out int ownerToken, out int paramCount)
    {
        name = "";
        signature = [];
        hasThis = false;
        ownerToken = 0;
        paramCount = 0;

        // 声明在外部类型（TypeRef）上的实例方法：拿不到类型定义，没法确证是引用类型还是值类型。
        TypeReferenceHandle externalOwner = default;

        switch (token >>> 24)
        {
            case 0x06: // MethodDef
            {
                int row = token & 0x00FFFFFF;
                if (row > _md.MethodDefinitions.Count)
                {
                    return false; // 注入的方法（__Decrypt / __AntiDebug / __Integrity / __Proxy 自己）
                }
                MethodDefinition m = _md.GetMethodDefinition(MetadataTokens.MethodDefinitionHandle(row));
                if ((m.Attributes & (MethodAttributes.PinvokeImpl | MethodAttributes.Abstract)) != 0)
                {
                    return false;
                }
                TypeDefinitionHandle declaring = m.GetDeclaringType();
                if (declaring.IsNil)
                {
                    return false;
                }
                // 泛型类型自己的实例方法：代理参数没法表达开泛型类型，跳过。
                if (m.GetGenericParameters().Count > 0
                    || _md.GetTypeDefinition(declaring).GetGenericParameters().Count > 0)
                {
                    return false;
                }
                if (IsValueType(declaring))
                {
                    return false;
                }
                // __Proxy 是同一程序集里的无关类型，只够得着 public/internal/protected internal。
                // private、private protected、protected 的目标从代理里调会抛 MethodAccessException。
                if (!ReachableFromSibling(m.Attributes))
                {
                    return false;
                }
                name = _md.GetString(m.Name);
                signature = _md.GetBlobBytes(m.Signature);
                ownerToken = MetadataTokens.GetToken(declaring);
                break;
            }
            case 0x0A: // MemberRef
            {
                MemberReference mr = _md.GetMemberReference(MetadataTokens.MemberReferenceHandle(token & 0x00FFFFFF));
                if (mr.Parent.Kind == HandleKind.TypeDefinition)
                {
                    if (IsValueType((TypeDefinitionHandle)mr.Parent))
                    {
                        return false;
                    }
                }
                else if (mr.Parent.Kind == HandleKind.TypeReference)
                {
                    externalOwner = (TypeReferenceHandle)mr.Parent;
                }
                else
                {
                    return false; // TypeSpec（泛型实例化）：接收者/签名带实参，整体跳过
                }
                name = _md.GetString(mr.Name);
                signature = _md.GetBlobBytes(mr.Signature);
                ownerToken = MetadataTokens.GetToken(mr.Parent);
                break;
            }
            default:
                return false; // MethodSpec / Field / 其它
        }

        if (!TryReadSignatureHead(signature, out hasThis, out paramCount))
        {
            return false;
        }

        // 静态调用不涉及接收者，一律可以代理。实例调用若声明在外部引用类型上，只能靠
        // 「编译器对值类型实例方法发 call、对引用类型实例方法发 callvirt」这条经验规则保守过滤。
        if (hasThis && !externalOwner.IsNil && (!isCallvirt || IsCoreValueType(externalOwner)))
        {
            return false;
        }
        return true;
    }

    /// <summary>读调用约定与参数个数，并确认签名里不含泛型参数（<c>!0</c>/<c>!!0</c>）。</summary>
    private static bool TryReadSignatureHead(byte[] signature, out bool hasThis, out int paramCount)
    {
        hasThis = false;
        paramCount = 0;
        if (signature.Length < 2)
        {
            return false;
        }
        int p = 0;
        byte cc = signature[p++];
        hasThis = (cc & 0x20) != 0;
        if ((cc & 0x0F) != 0x00 || (cc & 0x10) != 0 || (cc & 0x40) != 0)
        {
            return false; // 只处理 DEFAULT、非泛型、非显式 this
        }
        if (!TryReadCompressed(signature, ref p, out uint count))
        {
            return false;
        }
        paramCount = (int)count;
        bool generic = false;
        if (!TrySkipType(signature, ref p, ref generic))
        {
            return false;
        }
        for (int i = 0; i < paramCount; i++)
        {
            if (!TrySkipType(signature, ref p, ref generic))
            {
                return false;
            }
        }
        return !generic;
    }

    /// <summary>原签名 -> 代理静态方法签名：接收者若存在则前置为第一个参数，类型取声明类型。</summary>
    private static bool TryBuildSignature(byte[] signature, bool hasThis, int ownerToken, out byte[] proxySignature)
    {
        proxySignature = [];
        if (hasThis && ownerToken == 0)
        {
            return false;
        }
        int p = 0;
        byte cc = signature[p++];
        if ((cc & 0x0F) != 0x00 || (cc & 0x10) != 0 || (cc & 0x40) != 0)
        {
            return false;
        }
        if (!TryReadCompressed(signature, ref p, out uint count))
        {
            return false;
        }
        int retStart = p;
        bool generic = false;
        if (!TrySkipType(signature, ref p, ref generic) || generic)
        {
            return false;
        }
        byte[] ret = signature[retStart..p];
        byte[] rest = signature[p..];
        int total = (int)count + (hasThis ? 1 : 0);

        var outp = new List<byte>(signature.Length + 2) { 0x00 };
        AppendCompressed(outp, (uint)total);
        outp.AddRange(ret);
        if (hasThis)
        {
            // 接收者一律是引用类型：值类型的实例方法上面已经全部排除掉了。
            outp.Add(0x12); // ELEMENT_TYPE_CLASS
            outp.AddRange(SignatureCoding.TypeDefOrRefOrSpec(ownerToken));
        }
        outp.AddRange(rest);
        proxySignature = outp.ToArray();
        return true;
    }

    /// <summary>代理方法体：把接收者与实参按下标原样搬上栈，再转发到原目标。</summary>
    private static byte[] BuildForwarder(int paramCount, bool hasThis, bool isCallvirt, int target)
    {
        var il = new IlBuilder();
        int total = paramCount + (hasThis ? 1 : 0);
        for (int i = 0; i < total; i++)
        {
            il.Emit("ldarg", i);
        }
        // 调用方式必须照抄原调用点：callvirt 是虚分派，call 是非虚调用。
        // 把基类的 base.X() 改成 callvirt 会打回派生类的覆写，直接死循环。
        il.Emit(isCallvirt ? "callvirt" : "call", target);
        il.Emit("ret");
        return il.Build();
    }

    // ------------------------------------------------------------------
    // 类型判定
    // ------------------------------------------------------------------

    private bool IsValueType(TypeDefinitionHandle handle)
    {
        EntityHandle b = _md.GetTypeDefinition(handle).BaseType;
        while (!b.IsNil)
        {
            string ns;
            string simple;
            if (b.Kind == HandleKind.TypeReference)
            {
                TypeReference tr = _md.GetTypeReference((TypeReferenceHandle)b);
                ns = _md.GetString(tr.Namespace);
                simple = _md.GetString(tr.Name);
            }
            else if (b.Kind == HandleKind.TypeDefinition)
            {
                TypeDefinition bt = _md.GetTypeDefinition((TypeDefinitionHandle)b);
                ns = _md.GetString(bt.Namespace);
                simple = _md.GetString(bt.Name);
                if (ns == "System" && simple is "ValueType" or "Enum")
                {
                    return true;
                }
                b = bt.BaseType;
                continue;
            }
            else
            {
                return false;
            }
            return ns == "System" && simple is "ValueType" or "Enum";
        }
        return false;
    }

    private bool IsCoreValueType(TypeReferenceHandle handle)
    {
        TypeReference tr = _md.GetTypeReference(handle);
        return _md.GetString(tr.Namespace) == "System" && CoreValueTypes.Contains(_md.GetString(tr.Name));
    }

    /// <summary>同程序集里的无关类型能不能调到这个方法。protected 系对代理无效（代理不继承目标）。</summary>
    private static bool ReachableFromSibling(MethodAttributes attributes) =>
        (attributes & MethodAttributes.MemberAccessMask) is not
            (MethodAttributes.Private or MethodAttributes.Family or MethodAttributes.FamANDAssem);

    // ------------------------------------------------------------------
    // 签名元素走向
    // ------------------------------------------------------------------

    /// <summary>跳过一个类型编码。<paramref name="generic"/> 置位表示出现了 <c>!0</c>/<c>!!0</c>。</summary>
    private static bool TrySkipType(byte[] sig, ref int p, ref bool generic)
    {
        if (p >= sig.Length)
        {
            return false;
        }
        byte et = sig[p++];
        switch (et)
        {
            case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06:
            case 0x07: case 0x08: case 0x09: case 0x0A: case 0x0B: case 0x0C:
            case 0x0D: case 0x0E: case 0x16: case 0x18: case 0x19: case 0x1C:
                return true;
            case 0x0F: case 0x10: case 0x1D: case 0x45: // PTR / BYREF / SZARRAY / PINNED
                return TrySkipType(sig, ref p, ref generic);
            case 0x11: case 0x12: // VALUETYPE / CLASS
                return TryReadCompressed(sig, ref p, out _);
            case 0x13: case 0x1E: // VAR / MVAR
                generic = true;
                return TryReadCompressed(sig, ref p, out _);
            case 0x14: // ARRAY Type rank sizes loBounds
            {
                if (!TrySkipType(sig, ref p, ref generic) || !TryReadCompressed(sig, ref p, out uint rank))
                {
                    return false;
                }
                for (uint i = 0; i < rank; i++)
                {
                    if (!TryReadCompressed(sig, ref p, out _))
                    {
                        return false;
                    }
                }
                if (!TryReadCompressed(sig, ref p, out uint loCount))
                {
                    return false;
                }
                for (uint i = 0; i < loCount; i++)
                {
                    if (!TryReadCompressed(sig, ref p, out _))
                    {
                        return false;
                    }
                }
                return true;
            }
            case 0x15: // GENERICINST Type argCount args
            {
                if (!TrySkipType(sig, ref p, ref generic) || !TryReadCompressed(sig, ref p, out uint argc))
                {
                    return false;
                }
                for (uint i = 0; i < argc; i++)
                {
                    if (!TrySkipType(sig, ref p, ref generic))
                    {
                        return false;
                    }
                }
                return true;
            }
            case 0x1B: // FNPTR 完整方法签名
            {
                if (p >= sig.Length)
                {
                    return false;
                }
                byte cc = sig[p++];
                if ((cc & 0x10) != 0 && !TryReadCompressed(sig, ref p, out _))
                {
                    return false;
                }
                if (!TryReadCompressed(sig, ref p, out uint pc) || !TrySkipType(sig, ref p, ref generic))
                {
                    return false;
                }
                for (uint i = 0; i < pc; i++)
                {
                    if (!TrySkipType(sig, ref p, ref generic))
                    {
                        return false;
                    }
                }
                return true;
            }
            case 0x1F: case 0x20: // CMOD_REQD / CMOD_OPT
                return TryReadCompressed(sig, ref p, out _) && TrySkipType(sig, ref p, ref generic);
            case 0x41: // SENTINEL（vararg 参数分隔，前面已排除 vararg）
                return true;
            default:
                return false;
        }
    }

    private static bool TryReadCompressed(byte[] sig, ref int p, out uint value)
    {
        value = 0;
        if (p >= sig.Length)
        {
            return false;
        }
        byte b = sig[p++];
        if ((b & 0x80) == 0)
        {
            value = b;
            return true;
        }
        if ((b & 0xC0) == 0x80)
        {
            if (p >= sig.Length)
            {
                return false;
            }
            value = (uint)(((b & 0x3F) << 8) | sig[p++]);
            return true;
        }
        if ((b & 0xE0) == 0xC0)
        {
            if (p + 2 >= sig.Length)
            {
                return false;
            }
            value = (uint)(((b & 0x1F) << 24) | (sig[p] << 16) | (sig[p + 1] << 8) | sig[p + 2]);
            p += 3;
            return true;
        }
        return false;
    }

    private static void AppendCompressed(List<byte> to, uint value)
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