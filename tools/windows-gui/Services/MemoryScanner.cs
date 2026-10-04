using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;

namespace PaccManager.Services;

/// <summary>
/// 用户态内存特征码扫描（文档 §4.3）。经回环接口由 Java 探针驱动，只做「OpenProcess +
/// ReadProcessMemory + 逐字节 mask 比对」，不写内存、不注入、不挂钩。
///
/// <para>局限于用户态：不能读内核内存与被保护页面；DMA / 内核级作弊仍需内核驱动。
/// 对每个可读且已提交的私有/映像区域采样，命中返回地址列表。扫描受
/// <see cref="MaxScannedBytes"/> / <see cref="MaxRegions"/> 双重预算约束，超限即截断并附注，
/// 保证单次调用耗时有上界。</para>
///
/// <para>读不到的区域（权限不足 / 已换页）直接跳过，绝不让整个扫描失败。</para>
/// </summary>
public static class MemoryScanner
{
    private const int PROCESS_QUERY_INFORMATION = 0x0400;
    private const int PROCESS_VM_READ = 0x0010;
    private const uint MEM_COMMIT = 0x1000;
    private const uint PAGE_GUARD = 0x100;
    private const uint PAGE_NOACCESS = 0x01;
    // 可读页：READONLY / READWRITE / WRITECOPY / EXECUTE_READ / EXECUTE_READWRITE / EXECUTE_WRITECOPY
    private const uint PAGE_READABLE = 0x02 | 0x04 | 0x08 | 0x20 | 0x40 | 0x80;
    private const long MaxScannedBytes = 512L * 1024 * 1024;
    private const int MaxRegions = 200_000;
    private const int ReadChunk = 1 * 1024 * 1024;
    private const int MaxHits = 64;

    /// <summary>一次内存扫描结果。</summary>
    public sealed record ScanResult(bool Supported, IReadOnlyList<long> Addresses, string? Note);

    /// <summary>
    /// 按进程名扫描特征码。进程不存在或句柄打不开时返回 <c>Supported=false</c>，
    /// 调用方据此区分「扫不了」与「没命中」。
    /// </summary>
    public static ScanResult Scan(string processName, byte[] pattern, byte[] mask)
    {
        if (string.IsNullOrWhiteSpace(processName) || pattern.Length == 0 || pattern.Length != mask.Length)
            return new ScanResult(false, Array.Empty<long>(), "参数非法");

        var name = Path.GetFileNameWithoutExtension(processName);
        Process[] procs;
        try { procs = Process.GetProcessesByName(name); }
        catch { return new ScanResult(false, Array.Empty<long>(), "进程枚举失败"); }

        using Process? target = procs.Length > 0 ? procs[0] : null;
        for (int i = 1; i < procs.Length; i++) procs[i].Dispose();
        if (target == null)
            return new ScanResult(false, Array.Empty<long>(), "目标进程未运行");

        IntPtr handle = IntPtr.Zero;
        try
        {
            handle = OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_VM_READ, false, target.Id);
            if (handle == IntPtr.Zero)
                return new ScanResult(false, Array.Empty<long>(), "OpenProcess 被拒绝（权限不足或受保护）");

            var hits = new List<long>();
            long scanned = 0;
            int regions = 0;
            IntPtr address = IntPtr.Zero;
            var mbiSize = Marshal.SizeOf<MemoryBasicInformation>();

            while (regions++ < MaxRegions)
            {
                if (VirtualQueryEx(handle, address, out var mbi, mbiSize) == 0)
                    break;

                long regionSize = (long)mbi.RegionSize;
                if (regionSize <= 0)
                    break;

                bool readable = mbi.State == MEM_COMMIT
                                && mbi.Protect != PAGE_NOACCESS
                                && (mbi.Protect & PAGE_GUARD) == 0
                                && (mbi.Protect & PAGE_READABLE) != 0;
                if (readable)
                {
                    scanned += ScanRegion(handle, mbi.BaseAddress, regionSize, pattern, mask, hits);
                    if (scanned >= MaxScannedBytes || hits.Count >= MaxHits)
                        return new ScanResult(true, hits, "扫描上限截断");
                }

                IntPtr next = mbi.BaseAddress + (nint)regionSize;
                if (next.ToInt64() <= address.ToInt64()) // 溢出 / 不前进，防死循环
                    break;
                address = next;
            }

            return new ScanResult(true, hits, hits.Count == 0 ? "未命中" : null);
        }
        catch (Exception ex)
        {
            return new ScanResult(false, Array.Empty<long>(), "扫描异常：" + ex.GetType().Name);
        }
        finally
        {
            if (handle != IntPtr.Zero) CloseHandle(handle);
        }
    }

    /// <summary>扫描一段可读区域，返回实际读取字节数；命中地址追加进 <paramref name="hits"/>。</summary>
    private static long ScanRegion(IntPtr handle, IntPtr baseAddress, long regionSize,
                                   byte[] pattern, byte[] mask, List<long> hits)
    {
        long offset = 0;
        long read = 0;
        var buffer = new byte[Math.Min(ReadChunk, regionSize)];
        while (offset < regionSize && read < MaxScannedBytes && hits.Count < MaxHits)
        {
            int want = (int)Math.Min(buffer.Length, regionSize - offset);
            if (want < pattern.Length) break;

            if (!ReadProcessMemory(handle, baseAddress + (nint)offset, buffer, want, out IntPtr got) || got.ToInt64() <= 0)
            {
                // 该页读不到：跳过整块，继续下一块，避免拖垮整次扫描
                offset += want;
                continue;
            }
            int gotBytes = (int)got.ToInt64();
            read += gotBytes;

            for (int i = 0; i + pattern.Length <= gotBytes; i++)
            {
                if (Match(buffer, i, pattern, mask))
                {
                    hits.Add(baseAddress.ToInt64() + offset + i);
                    if (hits.Count >= MaxHits) return read;
                }
            }
            offset += gotBytes;
        }
        return read;
    }

    private static bool Match(byte[] buffer, int at, byte[] pattern, byte[] mask)
    {
        for (int j = 0; j < pattern.Length; j++)
        {
            if (mask[j] == 0x00) continue;              // 通配
            if (buffer[at + j] != pattern[j]) return false;
        }
        return true;
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

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr OpenProcess(int desiredAccess, bool inheritHandle, int processId);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr handle);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern int VirtualQueryEx(IntPtr handle, IntPtr address,
        out MemoryBasicInformation buffer, int length);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool ReadProcessMemory(IntPtr handle, IntPtr baseAddress,
        byte[] buffer, int size, out IntPtr bytesRead);
}