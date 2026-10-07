using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

namespace PaccManager.Services;

/// <summary>
/// 用户态注入痕迹检测（文档 §4.3 系统进程层）。经回环接口由 Java 探针驱动，
/// 只读不写：不注入、不改保护、不挂钩。
///
/// <para>四个维度：</para>
/// <list type="number">
///   <item>远程线程 —— 线程起始地址落在当前进程所有已加载模块之外（上限取模块区间做包含判定）；</item>
///   <item>可执行可写区 —— {@code PAGE_EXECUTE_READWRITE} 且非映像（{@code MEM_IMAGE}）的已提交区域；</item>
///   <item>可疑句柄 —— 其它进程持有本进程、且带写内存或建线程权限的句柄（靠句柄复制回本进程后
///         {@code GetProcessId} 比对目标 PID 确认指向）；</item>
///   <item>PendingApc —— 用户态无法枚举别的进程的 APC 队列，恒为 false。</item>
/// </list>
///
/// <para>每个维度各自包 try/catch，单维度失败只影响该维度：绝不让整个检测抛给调用方。
/// 进程不存在 / {@code OpenProcess} 被拒时返回 {@code Supported=false}。</para>
/// </summary>
public static class InjectionDetector
{
    private const int ProcessQueryInformation = 0x0400;
    private const int ProcessDupHandle = 0x0040;

    private const uint PageExecuteReadWrite = 0x40;
    private const uint MemImage = 0x1000_0000;
    private const long MinExecRegionSize = 4096;
    private const int MaxRegions = 100_000;
    private const int MaxExecRwRegions = 512;

    private const int SystemExtendedHandleInformation = 64;
    private const uint StatusInfoLengthMismatch = 0xC0000004;
    private const uint ProcessVmWrite = 0x0020;
    private const uint ProcessCreateThread = 0x0002;
    private const uint DangerousProcessAccess = ProcessVmWrite | ProcessCreateThread;
    private const int MaxSuspiciousHandles = 64;
    private const uint DuplicateSameAccess = 0x0002;

    /// <summary>一次注入痕迹检测的结论。</summary>
    public sealed record InjectionResult(bool Supported, int RemoteThreadCount, int ExecRwRegionCount,
        bool PendingApc, int SuspiciousHandleCount, string? Note);

    /// <summary>
    /// 按进程名检测注入痕迹。进程不存在或打不开句柄时返回 {@code Supported=false}，
    /// 调用方据此区分「没发现」与「检测不了」。
    /// </summary>
    public static InjectionResult Detect(string processName)
    {
        try
        {
            if (string.IsNullOrWhiteSpace(processName))
                return new InjectionResult(false, 0, 0, false, 0, "参数非法");

            Process[] procs;
            try { procs = Process.GetProcessesByName(Path.GetFileNameWithoutExtension(processName)); }
            catch { return new InjectionResult(false, 0, 0, false, 0, "进程枚举失败"); }

            using Process? target = procs.Length > 0 ? procs[0] : null;
            for (int i = 1; i < procs.Length; i++) procs[i].Dispose();
            if (target == null)
                return new InjectionResult(false, 0, 0, false, 0, "目标进程未运行");

            var ranges = CollectModuleRanges(target);
            int remoteThreads = CountRemoteThreads(target, ranges);

            IntPtr handle = IntPtr.Zero;
            try
            {
                handle = OpenProcess(ProcessQueryInformation, false, target.Id);
                if (handle == IntPtr.Zero)
                    return new InjectionResult(false, 0, 0, false, 0, "OpenProcess 被拒绝（权限不足或受保护）");

                var notes = new List<string>();
                int execRw = CountExecutableRwRegions(handle, notes);
                int suspicious = CountSuspiciousHandles(target.Id, notes);
                notes.Add("pending_apc_unavailable");

                return new InjectionResult(true, remoteThreads, execRw, false, suspicious,
                    string.Join(";", notes));
            }
            finally
            {
                if (handle != IntPtr.Zero) CloseHandle(handle);
            }
        }
        catch (Exception ex)
        {
            return new InjectionResult(false, 0, 0, false, 0, "检测异常：" + ex.GetType().Name);
        }
    }

    // ---------- 远程线程 ----------

    private static List<(long Start, long End)> CollectModuleRanges(Process target)
    {
        var ranges = new List<(long Start, long End)>();
        try
        {
            foreach (ProcessModule module in target.Modules)
            {
                try
                {
                    long start = module.BaseAddress.ToInt64();
                    long end = start + module.ModuleMemorySize;
                    if (start > 0 && module.ModuleMemorySize > 0) ranges.Add((start, end));
                }
                catch { /* 个别模块不可读 */ }
            }
        }
        catch { /* 模块枚举失败：ranges 为空，后续不判定 */ }
        return ranges;
    }

    private static int CountRemoteThreads(Process target, List<(long Start, long End)> ranges)
    {
        if (ranges.Count == 0) return 0; // 没有模块区间就无法判定，宁可返回 0 也不要计错
        int count = 0;
        try
        {
            foreach (ProcessThread thread in target.Threads)
            {
                try
                {
                    long start = thread.StartAddress.ToInt64();
                    if (start <= 0) continue; // 拿不到起始地址，跳过
                    if (!InAnyRange(ranges, start)) count++;
                }
                catch { /* 该线程起始地址不可读，跳过 */ }
                finally { thread.Dispose(); }
            }
        }
        catch { /* 线程枚举失败：返回已计数结果 */ }
        return count;
    }

    private static bool InAnyRange(List<(long Start, long End)> ranges, long value)
    {
        foreach (var (start, end) in ranges)
        {
            if (value >= start && value < end) return true;
        }
        return false;
    }

    // ---------- 可执行可写区 ----------

    private static int CountExecutableRwRegions(IntPtr handle, List<string> notes)
    {
        int count = 0;
        int regions = 0;
        IntPtr address = IntPtr.Zero;
        int mbiSize = Marshal.SizeOf<MemoryBasicInformation>();
        try
        {
            while (regions++ < MaxRegions)
            {
                if (VirtualQueryEx(handle, address, out var mbi, mbiSize) == 0) break;

                long regionSize = (long)mbi.RegionSize;
                if (regionSize <= 0) break;

                if (mbi.Protect == PageExecuteReadWrite && mbi.Type != MemImage
                    && regionSize >= MinExecRegionSize)
                {
                    if (count >= MaxExecRwRegions)
                    {
                        notes.Add("exec_rw_truncated");
                        break;
                    }
                    count++;
                }

                IntPtr next = mbi.BaseAddress + (nint)regionSize;
                if (next.ToInt64() <= address.ToInt64()) break; // 溢出 / 不前进，防死循环
                address = next;
            }
        }
        catch { /* 遍历中断：返回已计数结果 */ }
        return count;
    }

    // ---------- 可疑句柄 ----------

    private static int CountSuspiciousHandles(int targetPid, List<string> notes)
    {
        IntPtr buffer = IntPtr.Zero;
        var ownerHandles = new Dictionary<int, IntPtr>();
        IntPtr self = GetCurrentProcess();
        bool enumerated = false;
        int found = 0;
        try
        {
            uint size = 1u << 20;
            buffer = Marshal.AllocHGlobal((int)size);
            int status = NtQuerySystemInformation(SystemExtendedHandleInformation, buffer, size, out uint needed);
            if (status == unchecked((int)StatusInfoLengthMismatch) && needed > size)
            {
                Marshal.FreeHGlobal(buffer);
                size = needed + (1u << 16);
                buffer = Marshal.AllocHGlobal((int)size);
                status = NtQuerySystemInformation(SystemExtendedHandleInformation, buffer, size, out needed);
            }
            if (status != 0)
            {
                notes.Add("handle_enum_unavailable");
                return 0;
            }
            enumerated = true;

            long handleCount = Marshal.ReadIntPtr(buffer).ToInt64();
            IntPtr entries = buffer + IntPtr.Size * 2; // 头部为 NumberOfHandles + Reserved
            int entrySize = Marshal.SizeOf<SystemHandleEntryEx>();
            int offOwner = (int)Marshal.OffsetOf<SystemHandleEntryEx>(nameof(SystemHandleEntryEx.UniqueProcessId));
            int offGranted = (int)Marshal.OffsetOf<SystemHandleEntryEx>(nameof(SystemHandleEntryEx.GrantedAccess));
            int offHandleValue = (int)Marshal.OffsetOf<SystemHandleEntryEx>(nameof(SystemHandleEntryEx.HandleValue));

            for (long i = 0; i < handleCount && found < MaxSuspiciousHandles; i++)
            {
                IntPtr entry = entries + (nint)(i * entrySize);
                long ownerPid = Marshal.ReadIntPtr(entry, offOwner).ToInt64();
                if (ownerPid == 0 || ownerPid == targetPid) continue; // 内核句柄 / 进程自己持有的，跳过

                uint granted = (uint)Marshal.ReadInt32(entry, offGranted);
                if ((granted & DangerousProcessAccess) == 0) continue;

                if (!ownerHandles.TryGetValue((int)ownerPid, out IntPtr owner))
                {
                    owner = OpenProcess(ProcessDupHandle, false, (int)ownerPid);
                    ownerHandles[(int)ownerPid] = owner; // 打开失败也缓存 Zero，避免重复尝试
                }
                if (owner == IntPtr.Zero) continue;

                IntPtr sourceHandle = Marshal.ReadIntPtr(entry, offHandleValue);
                try
                {
                    if (!DuplicateHandle(owner, sourceHandle, self, out IntPtr dup, 0, false, DuplicateSameAccess))
                        continue;
                    try
                    {
                        if (GetProcessId(dup) == targetPid) found++;
                    }
                    finally { CloseHandle(dup); }
                }
                catch { /* 单个句柄复制失败，跳过 */ }
            }
        }
        catch
        {
            if (!enumerated) notes.Add("handle_enum_unavailable");
            found = 0;
        }
        finally
        {
            foreach (IntPtr h in ownerHandles.Values)
            {
                if (h != IntPtr.Zero) CloseHandle(h);
            }
            if (buffer != IntPtr.Zero) Marshal.FreeHGlobal(buffer);
        }
        return found;
    }

    // ---------- 平台调用 ----------

    [StructLayout(LayoutKind.Sequential)]
    private struct MemoryBasicInformation
    {
        public IntPtr BaseAddress;
        public IntPtr AllocationBase;
        public uint AllocationProtect;
        public nuint RegionSize;
        public uint State;
        public uint Protect;
        public uint Type;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct SystemHandleEntryEx
    {
        public IntPtr Object;
        public IntPtr UniqueProcessId;
        public IntPtr HandleValue;
        public uint GrantedAccess;
        public ushort CreatorBackTraceIndex;
        public ushort ObjectTypeIndex;
        public uint HandleAttributes;
        public uint Reserved;
    }

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(int desiredAccess, bool inheritHandle, int processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern int VirtualQueryEx(IntPtr handle, IntPtr address,
        out MemoryBasicInformation buffer, int length);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool DuplicateHandle(IntPtr hSourceProcessHandle, IntPtr hSourceHandle,
        IntPtr hTargetProcessHandle, out IntPtr lpTargetHandle, uint dwDesiredAccess,
        bool bInheritHandle, uint dwOptions);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern int GetProcessId(IntPtr handle);

    [DllImport("kernel32.dll")]
    private static extern IntPtr GetCurrentProcess();

    [DllImport("ntdll.dll")]
    private static extern int NtQuerySystemInformation(int systemInformationClass, IntPtr systemInformation,
        uint systemInformationLength, out uint returnLength);
}