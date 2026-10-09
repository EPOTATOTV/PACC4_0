using System.Reflection;

namespace PaccManager.Pco;

/// <summary>
/// 字符串加密：把方法体里的 <c>ldstr</c> 换成「密文字面量 + 调 __Decrypt::Get(string)」，
/// 原文不再进入重建后的 #US 堆，反编译/strings 看不到明文。
///
/// <para>密钥是每个程序集随机的一个字节，密文按位置混入 <c>key + i*0x1F</c> 的流，
/// XOR 自反，解密就是同一套运算再走一遍。注入的 __Decrypt 只做这一件事，不含任何外部依赖。</para>
/// </summary>
internal static class StringEncryptor
{
    private const int KeyStride = 0x1F;

    public static string Encrypt(string plain, byte key)
    {
        char[] chars = plain.ToCharArray();
        for (int i = 0; i < chars.Length; i++)
        {
            chars[i] = (char)(chars[i] ^ Keystream(key, i));
        }
        return new string(chars);
    }

    private static int Keystream(byte key, int index) => (key + index * KeyStride) & 0xFF;

    /// <summary>
    /// 构造 __Decrypt 类型，只含一个静态方法 <c>public static string Get(string)</c>：
    /// 把入参逐字符 XOR 回明文。密文与明文互为逆运算，方法本身对称。
    /// </summary>
    public static PendingType BuildDecryptType(AssemblyRewriter rewriter, byte key)
    {
        int stringRef = rewriter.RequireTypeRef("System", "String");
        int objectRef = rewriter.RequireTypeRef("System", "Object");

        // 实例方法签名：HASTHIS(0x20) + 参数个数 + 返回类型 + 参数类型。
        // char[]=SZARRAY(0x1D) CHAR(0x03)，void=0x01，string=0x0E，int32=0x08。
        byte[] toCharArraySig = [0x20, 0x00, 0x1D, 0x03];
        byte[] stringCtorSig = [0x20, 0x01, 0x01, 0x1D, 0x03];
        int toCharArray = rewriter.RequireMethodRef(stringRef, "ToCharArray", toCharArraySig);
        int stringCtor = rewriter.RequireMethodRef(stringRef, ".ctor", stringCtorSig);

        // 局部变量：char[] a; int i;
        int localRow = rewriter.ReserveLocalSignature([0x07, 0x02, 0x1D, 0x03, 0x08]);

        var il = new IlBuilder();
        IlLabel check = il.NewLabel();
        IlLabel loop = il.NewLabel();

        il.Emit("ldarg.0");
        il.Emit("callvirt", toCharArray);
        il.Emit("stloc.0");
        il.Emit("ldc.i4.0");
        il.Emit("stloc.1");
        il.Emit("br", check);

        il.Mark(loop);
        il.Emit("ldloc.0");
        il.Emit("ldloc.1");
        il.Emit("ldloc.0");
        il.Emit("ldloc.1");
        il.Emit("ldelem.u2");
        il.Emit("ldc.i4", (int)key);
        il.Emit("ldloc.1");
        il.Emit("ldc.i4", KeyStride);
        il.Emit("mul");
        il.Emit("add");
        il.Emit("ldc.i4", 0xFF);
        il.Emit("and");
        il.Emit("xor");
        il.Emit("conv.u2");
        il.Emit("stelem.i2");
        il.Emit("ldloc.1");
        il.Emit("ldc.i4.1");
        il.Emit("add");
        il.Emit("stloc.1");

        il.Mark(check);
        il.Emit("ldloc.1");
        il.Emit("ldloc.0");
        il.Emit("ldlen");
        il.Emit("conv.i4");
        il.Emit("blt", loop);

        il.Emit("ldloc.0");
        il.Emit("newobj", stringCtor);
        il.Emit("ret");

        var method = new PendingMethod
        {
            Name = "Get",
            Attributes = MethodAttributes.Public | MethodAttributes.Static | MethodAttributes.HideBySig,
            Signature = [0x00, 0x01, 0x0E, 0x0E], // static string Get(string)
            Il = il.Build(),
            MaxStack = 8,
            LocalSignatureRow = localRow,
        };
        method.Parameters.Add("s");

        var type = new PendingType
        {
            Namespace = "PaccManager",
            Name = "__Decrypt",
            Attributes = TypeAttributes.Public | TypeAttributes.Sealed | TypeAttributes.Abstract
                | TypeAttributes.BeforeFieldInit,
            BaseTypeToken = objectRef,
        };
        type.Methods.Add(method);
        return type;
    }
}