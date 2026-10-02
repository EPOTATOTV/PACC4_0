using System.Reflection;
using System.Reflection.Metadata;

namespace PaccManager.Pco;

/// <summary>
/// 反调试注入：往产物里塞一个 <c>PaccManager.__AntiDebug</c> 类型，并在入口方法（App.OnStartup）
/// 开头注入一次 <c>Start()</c> 调用。
///
/// <para><b>只做降级，不做自毁。</b>设计文档给的是「清配置 + 关进程」，但那对误报零容忍：
/// 开发工具、沙箱、甚至某些杀软都会让调试器标志为真，硬退会把正常用户挡在门外。
/// 这里改成把结果记进 <c>Detected</c> 静态标志，由调用方决定怎么用——检测精度降级，
/// 进程继续跑。这也是设计文档风险表里「先降级，不直接退出」的落法。</para>
///
/// <para>检测手段三条：托管调试器（Debugger.IsAttached）、本机调试器（kernel32 的
/// IsDebuggerPresent / CheckRemoteDebuggerPresent）。后两条走 P/Invoke，所以要给产物补
/// ModuleRef 与 ImplMap 行——注入管线已经支持。定时复检用 <c>System.Threading.Timer</c>，
/// 回调只做检测，不再造新的定时器。</para>
/// </summary>
internal static class AntiDebug
{
    /// <summary>注入类型里各方法的登记下标，IL 里的调用点靠它算 MethodDef 令牌。</summary>
    public const int StartIndex = 0;
    public const int ProbeIndex = 1;
    public const int IsDebuggerPresentIndex = 2;
    public const int CheckRemoteDebuggerPresentIndex = 3;
    public const int GetCurrentProcessIndex = 4;
    public const int CheckLoopIndex = 5;

    /// <summary>注入类型里各字段的登记下标。</summary>
    public const int DetectedFieldIndex = 0;
    public const int TimerFieldIndex = 1;

    private const int Detected = DetectedFieldIndex;

    private static byte[] Coded(int typeRefToken) => SignatureCoding.Coded(typeRefToken);

    public static PendingType BuildType(AssemblyRewriter rewriter, int methodBase, int fieldBase)
    {
        // Timer / TimerCallback / Debugger 在 .NET 8 都由 System.Runtime 转发到 CoreLib，
        // 引用作用域必须落在 System.Runtime，挂到 System.Threading 会解析不到类型。
        int objectRef = rewriter.RequireTypeRef("System", "Object");
        int debuggerRef = rewriter.RequireTypeRef("System.Diagnostics", "Debugger");
        int timerRef = rewriter.RequireTypeRef("System.Threading", "Timer");
        int timerCallbackRef = rewriter.RequireTypeRef("System.Threading", "TimerCallback");

        // P/Invoke 总要 ModuleRef；趁写元数据之前登记，否则 ModuleRef 表已经落完，补不进去。
        rewriter.RequireModuleRef("kernel32.dll");

        // Debugger.IsAttached 是静态属性，getter 没有 HASTHIS。
        int isAttached = rewriter.RequireMethodRef(debuggerRef, "get_IsAttached", [0x00, 0x00, 0x02]);
        int timerCtor = rewriter.RequireMethodRef(
            timerRef, ".ctor", [0x20, 0x04, 0x01, 0x12, .. Coded(timerCallbackRef), 0x1C, 0x08, 0x08]);
        int callbackCtor = rewriter.RequireMethodRef(
            timerCallbackRef, ".ctor", [0x20, 0x02, 0x01, 0x1C, 0x18]);

        // 本地令牌：类型内方法按登记顺序落行，这里按基址换算。
        int Token(int localIndex) => rewriter.InjectedMethodToken(methodBase + localIndex);
        int Field(int localIndex) => rewriter.InjectedFieldToken(fieldBase + localIndex);

        // Probe 的局部变量：bool remote;
        int probeLocals = rewriter.ReserveLocalSignature([0x07, 0x01, 0x02]);
        // Start 的局部变量：无；CheckLoop 无。

        var type = new PendingType
        {
            Namespace = "PaccManager",
            Name = "__AntiDebug",
            Attributes = TypeAttributes.Public | TypeAttributes.Sealed | TypeAttributes.Abstract
                | TypeAttributes.BeforeFieldInit,
            BaseTypeToken = objectRef,
        };
        type.Fields.Add(new PendingField
        {
            Name = "Detected",
            Attributes = FieldAttributes.Public | FieldAttributes.Static,
            Signature = [0x06, 0x02],
        });
        type.Fields.Add(new PendingField
        {
            Name = "Watchdog",
            Attributes = FieldAttributes.Private | FieldAttributes.Static,
            Signature = [0x06, 0x12, .. Coded(timerRef)],
        });

        type.Methods.Add(Start(Token(ProbeIndex), Token(CheckLoopIndex), callbackCtor, timerCtor,
            Field(TimerFieldIndex)));
        type.Methods.Add(Probe(isAttached, Token(IsDebuggerPresentIndex),
            Token(GetCurrentProcessIndex), Token(CheckRemoteDebuggerPresentIndex), Field(Detected), probeLocals));
        type.Methods.Add(Import("IsDebuggerPresent", [0x00, 0x00, 0x02]));
        type.Methods.Add(Import("CheckRemoteDebuggerPresent", [0x00, 0x02, 0x02, 0x18, 0x10, 0x02]));
        type.Methods.Add(Import("GetCurrentProcess", [0x00, 0x00, 0x18]));
        type.Methods.Add(CheckLoop(Token(ProbeIndex)));
        return type;
    }

    /// <summary>public static void Start()：先探一次，再挂上 10 秒一次的复检。</summary>
    private static PendingMethod Start(int probe, int checkLoop, int callbackCtor, int timerCtor, int timerField)
    {
        var il = new IlBuilder();
        IlLabel done = il.NewLabel();

        il.Emit("call", probe);
        // Watchdog 已存在就不再挂：避免被重复调用时堆出一串定时器。
        il.Emit("ldsfld", timerField);
        il.Emit("brtrue", done);
        il.Emit("ldnull");
        il.Emit("ldftn", checkLoop);
        il.Emit("newobj", callbackCtor);
        il.Emit("ldnull");
        il.Emit("ldc.i4", 10000);
        il.Emit("ldc.i4", 10000);
        il.Emit("newobj", timerCtor);
        il.Emit("stsfld", timerField);
        il.Mark(done);
        il.Emit("ret");

        return new PendingMethod
        {
            Name = "Start",
            Attributes = MethodAttributes.Public | MethodAttributes.Static | MethodAttributes.HideBySig,
            Signature = [0x00, 0x00, 0x01],
            Il = il.Build(),
            MaxStack = 8,
        };
    }

    /// <summary>private static void Probe()：三条检测，命中就置 Detected。</summary>
    private static PendingMethod Probe(int isAttached, int isDebuggerPresent, int getCurrentProcess,
        int checkRemote, int detectedField, int locals)
    {
        var il = new IlBuilder();
        IlLabel afterManaged = il.NewLabel();
        IlLabel afterNative = il.NewLabel();
        IlLabel done = il.NewLabel();

        // 1. 托管调试器
        il.Emit("call", isAttached);
        il.Emit("brfalse", afterManaged);
        il.Emit("ldc.i4.1");
        il.Emit("stsfld", detectedField);
        il.Mark(afterManaged);

        // 2. 本机调试器
        il.Emit("call", isDebuggerPresent);
        il.Emit("brfalse", afterNative);
        il.Emit("ldc.i4.1");
        il.Emit("stsfld", detectedField);
        il.Mark(afterNative);

        // 3. 远程调试器：CheckRemoteDebuggerPresent(GetCurrentProcess(), ref remote)
        il.Emit("call", getCurrentProcess);
        il.Emit("ldloca.s", 0);
        il.Emit("call", checkRemote);
        il.Emit("brfalse", done);
        il.Emit("ldloc.0");
        il.Emit("brfalse", done);
        il.Emit("ldc.i4.1");
        il.Emit("stsfld", detectedField);
        il.Mark(done);
        il.Emit("ret");

        return new PendingMethod
        {
            Name = "Probe",
            Attributes = MethodAttributes.Private | MethodAttributes.Static | MethodAttributes.HideBySig,
            Signature = [0x00, 0x00, 0x01],
            Il = il.Build(),
            MaxStack = 8,
            LocalSignatureRow = locals,
        };
    }

    /// <summary>private static void CheckLoop(object state)：定时器回调，只复检。</summary>
    private static PendingMethod CheckLoop(int probe)
    {
        var il = new IlBuilder();
        il.Emit("call", probe);
        il.Emit("ret");

        return new PendingMethod
        {
            Name = "CheckLoop",
            Attributes = MethodAttributes.Private | MethodAttributes.Static | MethodAttributes.HideBySig,
            Signature = [0x00, 0x01, 0x01, 0x1C],
            Il = il.Build(),
            MaxStack = 1,
        };
    }

    /// <summary>本机导出函数：kernel32 的调试器探测接口。</summary>
    private static PendingMethod Import(string entryPoint, byte[] signature) => new()
    {
        Name = entryPoint,
        Attributes = MethodAttributes.Private | MethodAttributes.Static | MethodAttributes.HideBySig
            | MethodAttributes.PinvokeImpl,
        Signature = signature,
        Il = [],
        MaxStack = 0,
        ImplAttributes = MethodImplAttributes.PreserveSig,
        Import = new PendingImport { Name = entryPoint, Module = "kernel32.dll" },
    };
}