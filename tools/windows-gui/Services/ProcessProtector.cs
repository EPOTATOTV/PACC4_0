using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;

namespace PaccManager.Services;

/// <summary>
/// 进程保护（L2 运行时层）：提权、收紧进程对象 DACL、启用缓解策略、崩溃守护。
///
/// <para>安全第一：所有措施都「只增强、不阻断」。DACL 收紧始终保留当前用户/SYSTEM/Administrators
/// 的完全访问，因此绝不可能把进程自己锁死；任何平台调用失败都静默降级，绝不抛出。</para>
///
/// <para>模式来源：环境变量 PACC_PROCPROTECT → 配置键 pacc.security.process-protect → 默认 "warn"。
/// off=完全不做任何保护动作；warn/exit=执行保护动作（本模块本身不退出进程）。</para>
/// </summary>
public static class ProcessProtector
{
    private const string EnvVar = "PACC_PROCPROTECT";
    private const string DefaultMode = "warn";

    /// <summary>最近一次 <see cref="Apply"/> 的生效情况（中文），供 GUI 展示。</summary>
    public static string LastApplied { get; private set; } = "(未执行)";

    // ---------- 平台调用：令牌提权 ----------

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool OpenProcessToken(IntPtr processHandle, uint desiredAccess, out IntPtr tokenHandle);

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool LookupPrivilegeValueW(string? systemName, string name, out LUID luid);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool AdjustTokenPrivileges(IntPtr tokenHandle, bool disableAllPrivileges,
        ref TOKEN_PRIVILEGES newState, uint bufferLength, IntPtr previousState, IntPtr returnLength);

    // ---------- 平台调用：DACL ----------

    [DllImport("advapi32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool ConvertStringSecurityDescriptorToSecurityDescriptorW(
        string stringSecurityDescriptor, uint stringSecurityDescriptorRevision,
        out IntPtr securityDescriptor, out uint securityDescriptorSize);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern bool GetSecurityDescriptorDacl(IntPtr securityDescriptor,
        out int daclPresent, out IntPtr dacl, out int daclDefaulted);

    [DllImport("advapi32.dll", SetLastError = true)]
    private static extern uint SetSecurityInfo(IntPtr handle, int objectType, int securityInfo,
        IntPtr sidOwner, IntPtr sidGroup, IntPtr dacl, IntPtr sacl);

    // ---------- 平台调用：缓解策略与通用 ----------

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(uint desiredAccess, bool inheritHandle, int processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr hMem);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool SetProcessMitigationPolicy(int mitigationPolicy, ref MitigationFlags lpBuffer, IntPtr dwLength);

    // ---------- 结构体与常量 ----------

    [StructLayout(LayoutKind.Sequential)]
    private struct LUID
    {
        public uint LowPart;
        public int HighPart;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct TOKEN_PRIVILEGES
    {
        public uint PrivilegeCount;
        public LUID Luid;
        public uint Attributes;
    }

    /// <summary>缓解策略标志载体（各策略结构均为单个 DWORD 位域）。</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct MitigationFlags
    {
        public uint Flags;
    }

    private const uint TOKEN_ADJUST_PRIVILEGES = 0x0020;
    private const uint TOKEN_QUERY = 0x0008;
    private const uint SE_PRIVILEGE_ENABLED = 0x0002;
    private const int ERROR_NOT_ALL_ASSIGNED = 1300;

    private const uint SDDL_REVISION_1 = 1;
    private const int SE_KERNEL_OBJECT = 6;
    private const int DACL_SECURITY_INFORMATION = 0x00000004;
    private const int PROTECTED_DACL_SECURITY_INFORMATION = unchecked((int)0x80000000);

    private const uint PROCESS_WRITE_DAC = 0x00040000;
    private const uint PROCESS_QUERY_INFORMATION = 0x00000400;
    private const uint READ_CONTROL = 0x00020000;

    private const int ProcessExtensionPointDisablePolicy = 6; // 禁用 AppInit/IME 等扩展点注入
    private const int ProcessSignaturePolicy = 8;             // 仅允许 Microsoft 签名模块（默认不启用）

    // ---------- 对外 API ----------

    /// <summary>
    /// 按模式执行进程保护动作，返回本次生效情况的中文描述。off 时不做任何动作。
    /// 任何单步失败都静默降级，不影响其它步骤。
    /// </summary>
    public static string Apply(string? mode)
    {
        string m = Environment.GetEnvironmentVariable(EnvVar) ?? mode ?? DefaultMode;

        if (m.Equals("off", StringComparison.OrdinalIgnoreCase))
        {
            LastApplied = "进程保护已禁用（off）";
            return LastApplied;
        }

        var parts = new List<string>
        {
            EnablePrivilege()
                ? "已提升 SeDebugPrivilege"
                : "未取得 SeDebugPrivilege（普通用户下属正常）",
            HardenDacl()
                ? "已收紧进程 DACL（仅当前用户/SYSTEM/Administrators）"
                : "进程 DACL 未变更（权限不足或已是受限状态）",
            ApplyMitigations(),
        };

        LastApplied = string.Join("；", parts);
        return LastApplied;
    }

    /// <summary>
    /// 尝试为当前进程令牌打开 SeDebugPrivilege。普通用户没有该权限属正常，失败静默返回 false。
    /// </summary>
    public static bool EnablePrivilege()
    {
        IntPtr token = IntPtr.Zero;
        try
        {
            if (!OpenProcessToken(GetCurrentProcess(), TOKEN_ADJUST_PRIVILEGES | TOKEN_QUERY, out token))
                return false;
            if (!LookupPrivilegeValueW(null, "SeDebugPrivilege", out LUID luid))
                return false;

            var tp = new TOKEN_PRIVILEGES
            {
                PrivilegeCount = 1,
                Luid = luid,
                Attributes = SE_PRIVILEGE_ENABLED,
            };
            if (!AdjustTokenPrivileges(token, false, ref tp, 0, IntPtr.Zero, IntPtr.Zero))
                return false;

            // 调用成功但并非所有权限都被赋予时 LastError 为 ERROR_NOT_ALL_ASSIGNED
            return Marshal.GetLastWin32Error() != ERROR_NOT_ALL_ASSIGNED;
        }
        catch
        {
            return false;
        }
        finally
        {
            if (token != IntPtr.Zero) CloseHandle(token);
        }
    }

    /// <summary>
    /// 把当前进程对象的 DACL 收紧为「仅当前用户 + SYSTEM + Administrators 完全访问」，
    /// 从而拒绝其它主体对其执行 VM 读写/终止/创建线程。
    ///
    /// <para>为何不锁死自己：SDDL 中始终保留当前用户与 Administrators 的 GA（完全访问），
    /// 当前进程以该用户身份运行，永远仍可操作自身对象。</para>
    /// </summary>
    public static bool HardenDacl()
    {
        IntPtr processHandle = IntPtr.Zero;
        IntPtr sd = IntPtr.Zero;
        try
        {
            var sid = WindowsIdentity.GetCurrent().User;
            if (sid == null) return false;

            // P = SE_DACL_PROTECTED（阻断继承）；GA = 完全访问
            string sddl = $"D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;{sid.Value})";
            if (!ConvertStringSecurityDescriptorToSecurityDescriptorW(
                    sddl, SDDL_REVISION_1, out sd, out _))
                return false;

            if (!GetSecurityDescriptorDacl(sd, out int present, out IntPtr dacl, out _) || present == 0)
                return false;

            processHandle = OpenProcess(PROCESS_WRITE_DAC | PROCESS_QUERY_INFORMATION | READ_CONTROL,
                false, Environment.ProcessId);
            if (processHandle == IntPtr.Zero) return false;

            uint rc = SetSecurityInfo(processHandle, SE_KERNEL_OBJECT,
                DACL_SECURITY_INFORMATION | PROTECTED_DACL_SECURITY_INFORMATION,
                IntPtr.Zero, IntPtr.Zero, dacl, IntPtr.Zero);
            return rc == 0;
        }
        catch
        {
            return false;
        }
        finally
        {
            if (processHandle != IntPtr.Zero) CloseHandle(processHandle);
            if (sd != IntPtr.Zero) LocalFree(sd);
        }
    }

    /// <summary>
    /// 启用进程缓解策略。默认只启用「扩展点注入禁用」，返回生效项的中文描述。
    ///
    /// <para>为何默认不启用 <c>ProcessSignaturePolicy</c>（Microsoft 签名策略）：一旦开启，
    /// 加载任何未签名的私有 DLL 都会被拒——可能让正常的插件/依赖失效，风险高于收益，故默认关闭。</para>
    /// </summary>
    public static string ApplyMitigations()
    {
        var applied = new List<string>();
        try
        {
            var buf = new MitigationFlags { Flags = 1 }; // DisableExtensionPoints = 1
            if (SetProcessMitigationPolicy(ProcessExtensionPointDisablePolicy,
                    ref buf, (IntPtr)Marshal.SizeOf<MitigationFlags>()))
                applied.Add("已启用扩展点注入禁用（ExtensionPointDisablePolicy）");
        }
        catch
        {
            // 策略不生效（已被固化/权限不足）时静默降级
        }

        return applied.Count > 0
            ? string.Join("；", applied)
            : "缓解策略未生效（可能已固化或权限不足）";
    }

    /// <summary>
    /// 注册崩溃守护：捕获 AppDomain 未处理异常并落盘。
    /// WPF 的 DispatcherUnhandledException 由 App.xaml.cs 挂钩后调用 <see cref="LogCrash"/>，
    /// 以保持本 Services 类不依赖 WPF。
    /// </summary>
    public static void InstallCrashGuard()
    {
        try
        {
            AppDomain.CurrentDomain.UnhandledException += (_, e) =>
            {
                try { LogCrash(e.ExceptionObject as Exception, "AppDomain.UnhandledException"); }
                catch { /* 日志写入失败不得引发二次异常 */ }
            };
        }
        catch
        {
            // 挂钩失败不影响主流程
        }
    }

    /// <summary>把未处理异常追加写入 %ProgramData%\PACC\pacc-manager-crash.log（失败静默）。</summary>
    public static void LogCrash(Exception? ex, string source)
    {
        try
        {
            string dir = Path.Combine(
                Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "PACC");
            Directory.CreateDirectory(dir);
            string file = Path.Combine(dir, "pacc-manager-crash.log");

            var sb = new StringBuilder();
            sb.AppendLine($"[{DateTime.Now:yyyy-MM-dd HH:mm:ss}] {source}");
            sb.AppendLine(ex?.ToString() ?? "(无异常对象)");
            sb.AppendLine();
            File.AppendAllText(file, sb.ToString(), Encoding.UTF8);
        }
        catch
        {
            // 写日志本身绝不允许再抛
        }
    }
}