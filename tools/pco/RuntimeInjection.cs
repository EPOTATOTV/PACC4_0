using System.Reflection;
using System.Reflection.Metadata;

namespace PaccManager.Pco;

/// <summary>签名 blob 里各种编码值的写法。</summary>
internal static class SignatureCoding
{
    /// <summary>TypeRef 的 TypeDefOrRefOrSpecEncoded（标记固定为 1）。</summary>
    public static byte[] Coded(int typeRefToken)
    {
        uint value = (uint)(((typeRefToken & 0x00FFFFFF) << 2) | 1);
        return Encode(value);
    }

    /// <summary>
    /// TypeDefOrRefOrSpecEncoded：按令牌高位选标记（TypeDef=0、TypeRef=1、TypeSpec=2），
    /// 再走压缩无符号整数编码。代理方法的接收者参数要把声明类型写进签名，用得到。
    /// </summary>
    public static byte[] TypeDefOrRefOrSpec(int token)
    {
        int marker = (token >>> 24) switch
        {
            0x02 => 0,
            0x01 => 1,
            0x1B => 2,
            _ => throw new ArgumentException($"不是类型令牌：0x{token:X8}", nameof(token)),
        };
        return Encode((uint)(((token & 0x00FFFFFF) << 2) | marker));
    }

    private static byte[] Encode(uint value) =>
        value <= 0x7F
            ? [(byte)value]
            : value <= 0x3FFF
                ? [(byte)(0x80 | (value >> 8)), (byte)value]
                : [(byte)(0xC0 | (value >> 24)), (byte)(value >> 16), (byte)(value >> 8), (byte)value];
}

/// <summary>
/// 待注入到目标程序集的新类型。注入一律追加在所有原有行之后，原有行号不变，
/// 所以原有 IL 里的类型/方法/字段令牌完全不用重映射（见 <see cref="AssemblyRewriter"/> 的约定）。
/// </summary>
internal sealed class PendingType
{
    public required string Namespace { get; init; }
    public required string Name { get; init; }
    public required TypeAttributes Attributes { get; init; }

    /// <summary>基类令牌。几乎总是 System.Object 的类型引用。</summary>
    public required int BaseTypeToken { get; init; }

    public List<PendingMethod> Methods { get; } = [];

    public List<PendingField> Fields { get; } = [];
}

/// <summary>待注入的静态字段。</summary>
internal sealed class PendingField
{
    public required string Name { get; init; }
    public required FieldAttributes Attributes { get; init; }

    /// <summary>字段签名 blob（FieldSig）。</summary>
    public required byte[] Signature { get; init; }
}

/// <summary>待注入的 P/Invoke 入口（ImplMap 行）。</summary>
internal sealed class PendingImport
{
    /// <summary>本机模块里的入口名。</summary>
    public required string Name { get; init; }

    /// <summary>本机模块名，如 "kernel32.dll"。</summary>
    public required string Module { get; init; }

    public MethodImportAttributes Attributes { get; init; } = MethodImportAttributes.CallingConventionWinApi;
}

/// <summary>待注入的方法。IL 已经是最终字节，直接搬进方法体流。</summary>
internal sealed class PendingMethod
{
    public required string Name { get; init; }
    public required MethodAttributes Attributes { get; init; }

    /// <summary>方法签名 blob（MethodDefSig）。</summary>
    public required byte[] Signature { get; init; }

    public required byte[] Il { get; init; }

    public int MaxStack { get; init; }

    /// <summary>局部变量签名行号；0 表示该方法没有局部变量。</summary>
    public int LocalSignatureRow { get; init; }

    public bool InitLocals { get; init; } = true;

    /// <summary>ImplFlags。P/Invoke 方法要 PreserveSig，普通托管方法用 IL|Managed。</summary>
    public MethodImplAttributes ImplAttributes { get; init; } =
        MethodImplAttributes.IL | MethodImplAttributes.Managed;

    /// <summary>非空表示这是个 P/Invoke 方法，写入时要补一条 ImplMap。</summary>
    public PendingImport? Import { get; init; }

    /// <summary>参数名，按顺序。</summary>
    public List<string> Parameters { get; } = [];
}