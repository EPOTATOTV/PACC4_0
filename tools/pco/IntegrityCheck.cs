namespace PaccManager.Pco;

/// <summary>
/// 完整性校验：产物序列化完成后，在文件末尾追加 32 字节 = 「PE 镜像部分的 SHA-256」。
/// 注入的 <c>PaccManager.__Integrity</c> 在入口处读自身文件，重算前一段的哈希，
/// 与尾部 32 字节逐字节比对（累加差异、不提前返回），不一致就把 <c>Detected</c> 置位。
///
/// <para><b>为什么用尾部而不是把哈希写进 IL：</b>哈希覆盖的是整个镜像，而 IL 属于镜像的一部分，
/// 「把哈希写进 IL 再哈希整份文件」是自指的，永远算不出来。放在 PE 之外的尾部就没有这个循环。
/// 代价是攻击者改完文件也能重算尾部——它挡的是无意的损坏与不做重算的粗暴补丁，
/// 不是有备而来的逆向者。这一点在注释里说清楚，别让人误以为它是密码学意义上的防篡改。</para>
///
/// <para><b>不硬退：</b>与反调试一致，命中只置标志，由调用方决定怎么处理。</para>
/// </summary>
internal static class IntegrityCheck
{
    /// <summary>尾部长度：SHA-256 摘要 32 字节。</summary>
    public const int TrailerSize = 32;

    /// <summary>注入类型里 Verify 方法的登记下标。</summary>
    public const int VerifyIndex = 0;

    /// <summary>注入类型里 Detected 字段的登记下标。</summary>
    public const int DetectedFieldIndex = 0;

    public static PendingType BuildType(AssemblyRewriter rewriter, int methodBase, int fieldBase)
    {
        int objectRef = rewriter.RequireTypeRef("System", "Object");
        int assemblyRef = rewriter.RequireTypeRef("System.Reflection", "Assembly");
        int fileRef = rewriter.RequireTypeRef("System.IO", "File");
        int arrayRef = rewriter.RequireTypeRef("System", "Array");
        // SHA256 在 .NET 8 里由 System.Security.Cryptography 定义，System.Runtime 不转发它，
        // 作用域必须显式指到那个程序集，挂到核心库上会解析不到。
        int shaRef = rewriter.RequireTypeRef(
            "System.Security.Cryptography", "SHA256", "System.Security.Cryptography");
        int byteRef = rewriter.RequireTypeRef("System", "Byte");

        byte[] codedAssembly = SignatureCoding.Coded(assemblyRef);
        byte[] codedArray = SignatureCoding.Coded(arrayRef);
        int getExecuting = rewriter.RequireMethodRef(
            assemblyRef, "GetExecutingAssembly", [0x00, 0x00, 0x12, .. codedAssembly]);
        int getLocation = rewriter.RequireMethodRef(assemblyRef, "get_Location", [0x20, 0x00, 0x0E]);
        // byte[] = SZARRAY(0x1D) U1(0x05)，别写成 I4(0x08)——那会声明成 int[]，运行期解析不到方法。
        int readAllBytes = rewriter.RequireMethodRef(fileRef, "ReadAllBytes", [0x00, 0x01, 0x1D, 0x05, 0x0E]);
        int arrayCopy = rewriter.RequireMethodRef(
            arrayRef, "Copy", [0x00, 0x03, 0x01, 0x12, .. codedArray, 0x12, .. codedArray, 0x08]);
        int hashData = rewriter.RequireMethodRef(shaRef, "HashData", [0x00, 0x01, 0x1D, 0x05, 0x1D, 0x05]);

        int detectedField = rewriter.InjectedFieldToken(fieldBase + DetectedFieldIndex);

        // 局部变量：byte[] all; int n; byte[] head; byte[] hash; int diff; int i;
        int locals = rewriter.ReserveLocalSignature([0x07, 0x06, 0x1D, 0x05, 0x08, 0x1D, 0x05, 0x1D, 0x05, 0x08, 0x08]);

        var il = new IlBuilder();
        IlLabel fail = il.NewLabel();
        IlLabel check = il.NewLabel();
        IlLabel loop = il.NewLabel();
        IlLabel done = il.NewLabel();

        il.Emit("call", getExecuting);
        il.Emit("callvirt", getLocation);
        il.Emit("call", readAllBytes);
        il.Emit("stloc.0");

        // 文件短于尾部长度：结构不对，直接判失败。
        il.Emit("ldloc.0");
        il.Emit("ldlen");
        il.Emit("conv.i4");
        il.Emit("ldc.i4", TrailerSize);
        il.Emit("ble", fail);

        il.Emit("ldloc.0");
        il.Emit("ldlen");
        il.Emit("conv.i4");
        il.Emit("ldc.i4", TrailerSize);
        il.Emit("sub");
        il.Emit("stloc.1");

        // head = new byte[n]；Array.Copy(all, head, n)
        il.Emit("ldloc.1");
        il.Emit("newarr", byteRef);
        il.Emit("stloc.2");
        il.Emit("ldloc.0");
        il.Emit("ldloc.2");
        il.Emit("ldloc.1");
        il.Emit("call", arrayCopy);

        // hash = SHA256.HashData(head)
        il.Emit("ldloc.2");
        il.Emit("call", hashData);
        il.Emit("stloc.3");

        il.Emit("ldc.i4.0");
        il.Emit("stloc.s", 4);
        il.Emit("ldc.i4.0");
        il.Emit("stloc.s", 5);
        il.Emit("br", check);

        il.Mark(loop);
        // diff |= hash[i] ^ all[n + i]
        il.Emit("ldloc.s", 4);
        il.Emit("ldloc.3");
        il.Emit("ldloc.s", 5);
        il.Emit("ldelem.u1");
        il.Emit("ldloc.0");
        il.Emit("ldloc.1");
        il.Emit("ldloc.s", 5);
        il.Emit("add");
        il.Emit("ldelem.u1");
        il.Emit("xor");
        il.Emit("or");
        il.Emit("stloc.s", 4);
        il.Emit("ldloc.s", 5);
        il.Emit("ldc.i4.1");
        il.Emit("add");
        il.Emit("stloc.s", 5);

        il.Mark(check);
        il.Emit("ldloc.s", 5);
        il.Emit("ldc.i4", TrailerSize);
        il.Emit("blt", loop);

        il.Emit("ldloc.s", 4);
        il.Emit("brfalse", done);

        il.Mark(fail);
        il.Emit("ldc.i4.1");
        il.Emit("stsfld", detectedField);

        il.Mark(done);
        il.Emit("ret");

        var method = new PendingMethod
        {
            Name = "Verify",
            Attributes = System.Reflection.MethodAttributes.Public | System.Reflection.MethodAttributes.Static
                | System.Reflection.MethodAttributes.HideBySig,
            Signature = [0x00, 0x00, 0x01],
            Il = il.Build(),
            MaxStack = 8,
            LocalSignatureRow = locals,
        };

        var type = new PendingType
        {
            Namespace = "PaccManager",
            Name = "__Integrity",
            Attributes = System.Reflection.TypeAttributes.Public | System.Reflection.TypeAttributes.Sealed
                | System.Reflection.TypeAttributes.Abstract | System.Reflection.TypeAttributes.BeforeFieldInit,
            BaseTypeToken = objectRef,
        };
        type.Fields.Add(new PendingField
        {
            Name = "Detected",
            Attributes = System.Reflection.FieldAttributes.Public | System.Reflection.FieldAttributes.Static,
            Signature = [0x06, 0x02],
        });
        type.Methods.Add(method);
        return type;
    }
}