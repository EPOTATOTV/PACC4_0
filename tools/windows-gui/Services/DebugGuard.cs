using System.Diagnostics;
using System.Runtime.InteropServices;

namespace PaccManager.Services;

/// <summary>
/// 反调试守卫（L2 运行时层）：维度化检测是否有调试器附着，加权打分后按模式告警/退出。
///
/// <para>默认只「记录 + 告警」，绝不阻断正常用户：分数低于 <see cref="Threshold"/> 时静默返回。
/// 单个弱信号（如 PEB 堆标志误报）不足以越过阈值，需多维命中才告警。</para>
///
/// <para>模式来源优先级：环境变量 PACC_ANTIDEBUG → 配置键 pacc.security.antidebug → 默认 "warn"。
/// warn=提示并继续；exit=提示后 Environment.Exit(-1)；off=完全禁用（直接返回，不做任何检测）。</para>
///
/// <para>所有平台调用均包在 try/catch 内：调用失败（权限不足/API 不存在）视为「该维度无法判定」，
/// 只影响本维度，不影响其它维度，也绝不让程序崩溃。</para>
/// </summary>
public static class DebugGuard
{
    /// <summary>告警阈值：命中维度权重之和 ≥ 30 才按模式处置，避免弱信号误报。</summary>
    public const int Threshold = 30;

    private const string EnvVar = "PACC_ANTIDEBUG";
    private const string DefaultMode = "warn";

    // ---------- 平台调用 ----------

    [DllImport("kernel32.dll")]
    private static extern bool IsDebuggerPresent();

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CheckRemoteDebuggerPresent(IntPtr hProcess, ref int pbDebuggerPresent);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();

    [DllImport("kernel32.dll")]
    private static extern int GetCurrentThreadId();

    [DllImport("ntdll.dll")]
    private static extern int NtQueryInformationProcess(
        IntPtr processHandle, int processInformationClass,
        ref PROCESS_BASIC_INFORMATION processInformation,
        int processInformationLength, out int returnLength);

    [DllImport("ntdll.dll")]
    private static extern int NtQueryInformationProcess(
        IntPtr processHandle, int processInformationClass,
        ref IntPtr processInformation,
        int processInformationLength, out int returnLength);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenThread(uint dwDesiredAccess, bool bInheritHandle, int dwThreadId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern int SuspendThread(IntPtr hThread);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern int ResumeThread(IntPtr hThread);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool GetThreadContext(IntPtr hThread, IntPtr lpContext);

    // ---------- 结构体与常量 ----------

    /// <summary>PROCESS_BASIC_INFORMATION：仅取 InheritedFromUniqueProcessId 用。</summary>
    [StructLayout(LayoutKind.Sequential)]
    private struct PROCESS_BASIC_INFORMATION
    {
        public IntPtr ExitStatus;
        public IntPtr PebBaseAddress;
        public IntPtr AffinityMask;
        public int BasePriority;
        public IntPtr UniqueProcessId;
        public IntPtr InheritedFromUniqueProcessId;
    }

    private const int ProcessBasicInformation = 0;   // 父进程
    private const int ProcessDebugPort = 7;          // 调试端口
    private const int ProcessDebugObjectHandle = 0x1E; // 调试对象句柄

    private const uint THREAD_SUSPEND_RESUME = 0x0002;
    private const uint THREAD_GET_CONTEXT = 0x0008;
    private const uint THREAD_QUERY_INFORMATION = 0x0040;

    // CONTEXT 的 ContextFlags 取值（x86/x64 常量不同）。
    private const uint CONTEXT_DEBUG_REGISTERS_X64 = 0x00100010;
    private const uint CONTEXT_DEBUG_REGISTERS_X86 = 0x00010010;

    // CONTEXT 中 Dr0-Dr3/Dr7 的偏移（x86 与 x64 布局不同）。
    private static int ContextFlagsOffset => IntPtr.Size == 8 ? 0x30 : 0x00;
    private static int Dr0Offset => IntPtr.Size == 8 ? 0x48 : 0x04;
    private static int Dr7Offset => IntPtr.Size == 8 ? 0x70 : 0x18;

    /// <summary>PEB 中 NtGlobalFlag / ProcessHeap 的偏移（随位数不同）。</summary>
    private static int PebNtGlobalFlagOffset => IntPtr.Size == 8 ? 0xBC : 0x68;
    private static int PebProcessHeapOffset => IntPtr.Size == 8 ? 0x30 : 0x18;

    /// <summary>_HEAP 中 Flags / ForceFlags 的偏移（随位数不同）。</summary>
    private static int HeapFlagsOffset => IntPtr.Size == 8 ? 0x70 : 0x40;
    private static int HeapForceFlagsOffset => IntPtr.Size == 8 ? 0x74 : 0x44;

    /// <summary>HEAP_GROWABLE：未调试进程默认堆 Flags 的常态值，判异常时必须掩掉，否则人人命中。</summary>
    private const int HeapGrowable = 0x00000002;

    /// <summary>常见调试器/注入工具进程名（前缀匹配，避免 nvidia 之类误命中）。</summary>
    private static readonly string[] DebuggerProcesses =
    {
        "ollydbg", "x64dbg", "x32dbg", "windbg", "ida", "dnspy",
        "cheatengine", "frida", "httpdebugger", "processhacker", "scylla", "de4dot",
    };

    /// <summary>可能挂载调试器的 IDE/调试宿主进程名。</summary>
    private static readonly string[] IdeProcesses =
    {
        "devenv", "rider64", "rider", "code", "vsdebugmonitor", "vsjitdebugger", "dnspy",
    };

    // ---------- 对外状态 ----------

    private static readonly object Gate = new();
    private static int _lastScore;
    private static IReadOnlyList<string> _lastFindings = Array.Empty<string>();

    /// <summary>最近一次 <see cref="Assess"/> 的加权总分；未评估时为 0。</summary>
    public static int LastScore
    {
        get { lock (Gate) return _lastScore; }
    }

    /// <summary>最近一次命中的维度描述（只读视图，中文）。</summary>
    public static IReadOnlyList<string> LastFindings
    {
        get { lock (Gate) return _lastFindings; }
    }

    /// <summary>
    /// 执行全部检测维度、填充 <see cref="LastScore"/>/<see cref="LastFindings"/>，返回加权总分。
    /// 本方法不做任何告警/退出，便于 GUI 诊断页随时取数。
    /// </summary>
    public static int Assess()
    {
        var findings = new List<string>();
        int score = 0;

        // 10 个维度，每个维度独立执行；单个维度抛异常不影响其它维度（各维度内部已 try/catch）。
        Score(ManagedDebugger, ref score, findings);
        Score(PebBeingDebugged, ref score, findings);
        Score(RemoteDebugger, ref score, findings);
        Score(PebHeapFlags, ref score, findings);
        Score(DebugPort, ref score, findings);
        Score(DebugObjectHandle, ref score, findings);
        Score(DebuggerProcessScan, ref score, findings);
        Score(ParentProcessCheck, ref score, findings);
        Score(HardwareBreakpoints, ref score, findings);
        Score(TimingCheck, ref score, findings);

        lock (Gate)
        {
            _lastScore = score;
            _lastFindings = findings.AsReadOnly();
        }
        return score;
    }

    /// <summary>
    /// 检测并（按需）处置调试附着。分数低于 <see cref="Threshold"/> 时恒返回 false、零打扰；
    /// 达到阈值时按模式返回 true（warn 提示/exit 退出）。mode=off 时不做任何检测，直接返回 false。
    /// </summary>
    public static bool DetectAndDisrupt(string? configValue)
    {
        string mode = Environment.GetEnvironmentVariable(EnvVar)
                      ?? configValue
                      ?? DefaultMode;

        if (mode.Equals("off", StringComparison.OrdinalIgnoreCase))
            return false;

        int score = Assess();
        if (score < Threshold)
            return false;

        string msg = BuildMessage(score);

        if (mode.Equals("exit", StringComparison.OrdinalIgnoreCase))
        {
            System.Windows.MessageBox.Show(msg, "PACC 受保护", System.Windows.MessageBoxButton.OK,
                System.Windows.MessageBoxImage.Warning);
            System.Environment.Exit(-1);
            return true;
        }

        // 默认 warn：仅提示、继续运行
        System.Windows.MessageBox.Show(msg + "\n\n本次运行将继续。", "PACC 受保护",
            System.Windows.MessageBoxButton.OK, System.Windows.MessageBoxImage.Warning);
        return true;
    }

    // ---------- 内部辅助 ----------

    /// <summary>命中的维度计入总分并记录中文描述。</summary>
    private static void Score(Func<(bool detected, int weight, string description)> probe,
        ref int score, List<string> findings)
    {
        try
        {
            var (detected, weight, description) = probe();
            if (!detected) return;
            score += weight;
            findings.Add($"{description}（+{weight}）");
        }
        catch
        {
            // 维度自身异常视为「无法判定」，跳过
        }
    }

    private static string BuildMessage(int score)
    {
        var sb = new System.Text.StringBuilder();
        sb.AppendLine($"检测到调试环境迹象（可疑度 {score}）。本程序受保护，请在未调试的环境下运行。");
        IReadOnlyList<string> findings;
        lock (Gate) findings = _lastFindings;
        foreach (var f in findings) sb.AppendLine("  · " + f);
        return sb.ToString().TrimEnd();
    }

    // ---------- 检测维度 ----------

    /// <summary>维度 1：托管调试器（Debugger.IsAttached）。最可靠。</summary>
    private static (bool, int, string) ManagedDebugger()
    {
        return Debugger.IsAttached
            ? (true, 40, "托管调试器已附加（Debugger.IsAttached）")
            : (false, 40, string.Empty);
    }

    /// <summary>维度 2：PEB.BeingDebugged（IsDebuggerPresent）。最可靠。</summary>
    private static (bool, int, string) PebBeingDebugged()
    {
        return IsDebuggerPresent()
            ? (true, 40, "PEB.BeingDebugged 已置位（IsDebuggerPresent）")
            : (false, 40, string.Empty);
    }

    /// <summary>维度 3：远程调试（CheckRemoteDebuggerPresent）。</summary>
    private static (bool, int, string) RemoteDebugger()
    {
        int present = 0;
        if (CheckRemoteDebuggerPresent(GetCurrentProcess(), ref present) && present != 0)
            return (true, 35, "存在远程调试器（CheckRemoteDebuggerPresent）");
        return (false, 35, string.Empty);
    }

    /// <summary>
    /// 维度 4：PEB 堆调试标志。合并两项同源信号：
    /// PEB.NtGlobalFlag 的堆调试位（0x70 掩码）与默认堆的 Flags/ForceFlags。
    /// 后者是常见误报源，故本维度权重偏低，单命中不会越过阈值。
    ///
    /// <para><b>堆标志必须掩掉 HEAP_GROWABLE（0x2）</b>：未调试进程的默认堆 Flags 常态就是
    /// 0x2、ForceFlags 为 0，若直接判 <c>flags != 0</c> 会把每一台正常机器都算成命中。</para>
    /// </summary>
    private static (bool, int, string) PebHeapFlags()
    {
        IntPtr peb = GetPebBaseAddress();
        if (peb == IntPtr.Zero) return (false, 15, string.Empty);

        var signals = new List<string>();

        int globalFlag = Marshal.ReadInt32(peb, PebNtGlobalFlagOffset);
        if ((globalFlag & 0x70) == 0x70)
            signals.Add($"NtGlobalFlag=0x{globalFlag:X}");

        IntPtr heap = Marshal.ReadIntPtr(peb, PebProcessHeapOffset);
        if (heap != IntPtr.Zero)
        {
            int flags = Marshal.ReadInt32(heap, HeapFlagsOffset);
            int forceFlags = Marshal.ReadInt32(heap, HeapForceFlagsOffset);
            if ((flags & ~HeapGrowable) != 0 || forceFlags != 0)
                signals.Add($"HeapFlags=0x{flags:X}, HeapForceFlags=0x{forceFlags:X}");
        }

        if (signals.Count > 0)
            return (true, 15, "PEB/堆调试标志异常（" + string.Join("；", signals) + "）");
        return (false, 15, string.Empty);
    }

    /// <summary>维度 5：NtQueryInformationProcess(ProcessDebugPort)。非 0 即有调试端口。</summary>
    private static (bool, int, string) DebugPort()
    {
        IntPtr port = IntPtr.Zero;
        int status = NtQueryInformationProcess(GetCurrentProcess(), ProcessDebugPort,
            ref port, IntPtr.Size, out _);
        if (status == 0 && port != IntPtr.Zero)
            return (true, 40, $"进程存在调试端口（ProcessDebugPort={port.ToInt64()})");
        return (false, 40, string.Empty);
    }

    /// <summary>维度 6：NtQueryInformationProcess(ProcessDebugObjectHandle)。有句柄即被调试。</summary>
    private static (bool, int, string) DebugObjectHandle()
    {
        IntPtr handle = IntPtr.Zero;
        int status = NtQueryInformationProcess(GetCurrentProcess(), ProcessDebugObjectHandle,
            ref handle, IntPtr.Size, out _);
        if (status == 0 && handle != IntPtr.Zero)
            return (true, 40, "进程存在调试对象句柄（ProcessDebugObjectHandle）");
        return (false, 40, string.Empty);
    }

    /// <summary>维度 7：扫描进程列表中的常见调试器/注入工具。</summary>
    private static (bool, int, string) DebuggerProcessScan()
    {
        var hits = new List<string>();
        try
        {
            foreach (var p in Process.GetProcesses())
            {
                try
                {
                    string name = p.ProcessName;
                    foreach (var token in DebuggerProcesses)
                    {
                        if (name.StartsWith(token, StringComparison.OrdinalIgnoreCase))
                        {
                            hits.Add(name);
                            break;
                        }
                    }
                }
                catch { /* 单个进程查询失败忽略 */ }
                finally { p.Dispose(); }
            }
        }
        catch
        {
            return (false, 25, string.Empty); // 枚举进程整体失败，视为无法判定
        }

        if (hits.Count > 0)
            return (true, 25, "发现调试/注入工具进程：" + string.Join(", ", hits.Distinct()));
        return (false, 25, string.Empty);
    }

    /// <summary>维度 8：父进程是否为 IDE/调试宿主。从 IDE 启动属正常用法，权重较低。</summary>
    private static (bool, int, string) ParentProcessCheck()
    {
        int parentPid = GetParentProcessId();
        if (parentPid <= 0) return (false, 15, string.Empty);

        try
        {
            using var parent = Process.GetProcessById(parentPid);
            string name = parent.ProcessName;
            foreach (var token in IdeProcesses)
            {
                if (name.StartsWith(token, StringComparison.OrdinalIgnoreCase))
                    return (true, 15, $"父进程为 IDE/调试宿主：{name} (PID {parentPid})");
            }
        }
        catch
        {
            return (false, 15, string.Empty);
        }
        return (false, 15, string.Empty);
    }

    /// <summary>
    /// 维度 9：硬件断点（DR 寄存器）。逐个挂起线程后读 CONTEXT_DEBUG_REGISTERS。
    /// OpenThread/GetThreadContext 权限不足时视为无法判定，立即恢复线程。
    /// 权重取 25（低于阈值）：某些 EDR 也会占用 DR 寄存器，避免单信号误触告警。
    /// </summary>
    private static (bool, int, string) HardwareBreakpoints()
    {
        int currentTid = GetCurrentThreadId();
        int hitCount = 0;
        uint contextFlags = IntPtr.Size == 8 ? CONTEXT_DEBUG_REGISTERS_X64 : CONTEXT_DEBUG_REGISTERS_X86;
        int bufferSize = IntPtr.Size == 8 ? 0x500 : 0x300;

        // CONTEXT 缓冲必须在挂起任何线程之前分配好。
        // 若在 SuspendThread 之后再 AllocHGlobal，一旦被挂起的线程正持有进程堆锁，
        // 本线程就会永远卡在分配上——对一个「绝不阻断正常用户」的守卫来说，
        // 卡死比误报严重得多。分配只做一次，各线程复用。
        IntPtr ctx = IntPtr.Zero;
        try
        {
            ctx = Marshal.AllocHGlobal(bufferSize);

            foreach (ProcessThread t in Process.GetCurrentProcess().Threads)
            {
                int tid = t.Id;
                if (tid == currentTid) continue; // 挂起自身会死锁

                IntPtr hThread = IntPtr.Zero;
                bool suspended = false;
                try
                {
                    hThread = OpenThread(THREAD_GET_CONTEXT | THREAD_SUSPEND_RESUME | THREAD_QUERY_INFORMATION,
                        false, tid);
                    if (hThread == IntPtr.Zero) continue;

                    if (SuspendThread(hThread) == -1) continue;
                    suspended = true;

                    // 先清零并把 ContextFlags 放在正确偏移，避免脏数据被当作上下文
                    for (int i = 0; i < bufferSize; i++) Marshal.WriteByte(ctx, i, 0);
                    Marshal.WriteInt32(ctx, ContextFlagsOffset, unchecked((int)contextFlags));

                    if (!GetThreadContext(hThread, ctx)) continue;

                    for (int dr = 0; dr < 4; dr++)
                    {
                        long value = IntPtr.Size == 8
                            ? Marshal.ReadInt64(ctx, Dr0Offset + dr * 8)
                            : Marshal.ReadInt32(ctx, Dr0Offset + dr * 4);
                        if (value != 0) { hitCount++; break; }
                    }
                }
                catch { /* 单线程无法判定，跳过 */ }
                finally
                {
                    if (hThread != IntPtr.Zero)
                    {
                        if (suspended) ResumeThread(hThread);
                        CloseHandle(hThread);
                    }
                }
            }
        }
        catch
        {
            return (false, 25, string.Empty);
        }
        finally
        {
            if (ctx != IntPtr.Zero) Marshal.FreeHGlobal(ctx);
        }

        if (hitCount > 0)
            return (true, 25, $"检测到硬件断点（{hitCount} 个线程的 DR0-Dr3 非空）");
        return (false, 25, string.Empty);
    }

    /// <summary>
    /// 维度 10：时序检测。循环调用 IsDebuggerPresent，被下断点会显著变慢。
    /// 阈值给得极宽松，权重也低，仅作辅助信号、避免误报。
    /// </summary>
    private static (bool, int, string) TimingCheck()
    {
        var sw = Stopwatch.StartNew();
        int sink = 0;
        for (int i = 0; i < 1000; i++)
        {
            if (IsDebuggerPresent()) sink++;
        }
        sw.Stop();
        _ = sink;
        // 正常机器 1000 次调用远低于 5ms；放宽到 200ms 仍超时，才认为被单步/断点拖慢
        if (sw.ElapsedMilliseconds > 200)
            return (true, 10, $"时序异常：IsDebuggerPresent 基准调用耗时 {sw.ElapsedMilliseconds} ms");
        return (false, 10, string.Empty);
    }

    // ---------- 取 PEB / 父进程 ----------

    /// <summary>通过 NtQueryInformationProcess 取本进程 PEB 基址；失败返回 Zero。</summary>
    private static IntPtr GetPebBaseAddress()
    {
        var pbi = new PROCESS_BASIC_INFORMATION();
        int status = NtQueryInformationProcess(GetCurrentProcess(), ProcessBasicInformation,
            ref pbi, Marshal.SizeOf<PROCESS_BASIC_INFORMATION>(), out _);
        return status == 0 ? pbi.PebBaseAddress : IntPtr.Zero;
    }

    /// <summary>取父进程 PID；失败返回 -1。</summary>
    private static int GetParentProcessId()
    {
        var pbi = new PROCESS_BASIC_INFORMATION();
        int status = NtQueryInformationProcess(GetCurrentProcess(), ProcessBasicInformation,
            ref pbi, Marshal.SizeOf<PROCESS_BASIC_INFORMATION>(), out _);
        return status == 0 ? pbi.InheritedFromUniqueProcessId.ToInt32() : -1;
    }
}