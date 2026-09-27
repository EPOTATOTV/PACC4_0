using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

namespace PaccManager.Services;

/// <summary>
/// Hook 检测器（L2 运行时层）：IAT / Inline / EAT 三类 Hook 的启发式探测。
///
/// <para>默认只「记录 + 告警」，绝不阻断正常用户：模式来源优先级为
/// 环境变量 PACC_ANTIHOOK → 配置键 pacc.security.antihook → 默认 "warn"；
/// warn=提示并继续；exit=提示后 Environment.Exit(-1)；off=完全禁用。</para>
///
/// <para>所有内存读取/平台调用均包在 try/catch 内：读不到即视为「该维度无法判定」，
/// 只影响本维度，绝不让程序崩溃。</para>
///
/// <para>说明：PE 解析只针对「已加载到内存的映像」，此时 RVA 与内存偏移一致（base+RVA），
/// 无需再做节表换算，降低了 x64 下结构体布局出错的风险。</para>
/// </summary>
public static class HookDetector
{
    private const string EnvVar = "PACC_ANTIHOOK";
    private const string DefaultMode = "warn";
    private const int MaxFindings = 100;

    // ---------- 平台调用 ----------

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, ExactSpelling = true, SetLastError = true)]
    private static extern IntPtr GetModuleHandleA(string lpModuleName);

    [DllImport("kernel32.dll", CharSet = CharSet.Ansi, ExactSpelling = true, SetLastError = true,
        EntryPoint = "GetProcAddress")]
    private static extern IntPtr GetProcAddressByName(IntPtr hModule, string lpProcName);

    [DllImport("kernel32.dll", ExactSpelling = true, SetLastError = true,
        EntryPoint = "GetProcAddress")]
    private static extern IntPtr GetProcAddressByOrdinal(IntPtr hModule, IntPtr lpOrdinal);

    // ---------- 对外类型 ----------

    /// <summary>一次扫描的结论：三类 Hook 各自的命中描述列表（中文）。</summary>
    public sealed record HookReport(
        IReadOnlyList<string> IatHooks,
        IReadOnlyList<string> InlineHooks,
        IReadOnlyList<string> EatHooks)
    {
        /// <summary>命中总数。</summary>
        public int Total => IatHooks.Count + InlineHooks.Count + EatHooks.Count;

        /// <summary>是否未发现任何可疑项。</summary>
        public bool Clean => Total == 0;

        /// <summary>多行中文摘要，供 GUI 直接展示。</summary>
        public string Describe()
        {
            if (Clean) return "未发现 Hook 迹象。";
            var sb = new StringBuilder();
            void Section(string title, IReadOnlyList<string> items)
            {
                if (items.Count == 0) return;
                sb.AppendLine($"  {title}（{items.Count}）:");
                foreach (var it in items) sb.AppendLine("    · " + it);
            }
            Section("IAT Hook", IatHooks);
            Section("Inline Hook", InlineHooks);
            Section("EAT Hook", EatHooks);
            return sb.ToString().TrimEnd();
        }
    }

    // ---------- 对外 API ----------

    /// <summary>
    /// 执行 IAT / Inline / EAT 三类扫描，返回结构化结论。本方法不做任何告警，
    /// 便于 GUI 诊断页随时取数。
    /// </summary>
    public static HookReport Scan()
    {
        var iat = new List<string>();
        var inline = new List<string>();
        var eat = new List<string>();

        Guard(() => ScanIat(iat));
        Guard(() => ScanInline(inline));
        Guard(() => ScanEat("kernel32.dll", eat));
        Guard(() => ScanEat("ntdll.dll", eat));

        return new HookReport(iat.AsReadOnly(), inline.AsReadOnly(), eat.AsReadOnly());
    }

    /// <summary>
    /// 扫描并按模式告警。未发现 Hook 时恒返回 false、零打扰；发现 Hook 时按模式返回 true。
    /// mode=off 时不做任何检测，直接返回 false。
    /// </summary>
    public static bool DetectAndReport(string? configValue)
    {
        string mode = Environment.GetEnvironmentVariable(EnvVar)
                      ?? configValue
                      ?? DefaultMode;

        if (mode.Equals("off", StringComparison.OrdinalIgnoreCase))
            return false;

        var report = Scan();
        if (report.Clean)
            return false;

        string msg = $"检测到疑似 Hook 迹象（共 {report.Total} 项）。本程序受保护，可能存在注入/劫持。\n\n"
                     + report.Describe();

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

    // ---------- IAT Hook ----------

    /// <summary>
    /// 遍历本进程主模块的导入表：逐个 IAT 表项与 GetProcAddress 解析出的地址比对，
    /// 不一致即视为被改写（常见于 IAT Hook / 劫持）。
    /// </summary>
    private static void ScanIat(List<string> findings)
    {
        var mainModule = Process.GetCurrentProcess().MainModule;
        if (mainModule == null) return;
        IntPtr baseAddr = mainModule.BaseAddress;

        if (!TryGetDataDirectory(baseAddr, 1, out int importRva, out _, out _)) return;
        IntPtr desc = baseAddr + importRva;

        for (int i = 0; i < 4096 && findings.Count < MaxFindings; i++)
        {
            IntPtr d = desc + i * 20;
            int originalFirstThunk = Marshal.ReadInt32(d, 0);
            int nameRva = Marshal.ReadInt32(d, 12);
            int firstThunk = Marshal.ReadInt32(d, 16);
            if (originalFirstThunk == 0 && nameRva == 0 && firstThunk == 0) break; // 全零 = 结束
            if (firstThunk == 0 || nameRva == 0) continue;

            string dllName = ReadAsciiZ(baseAddr + nameRva, 256);
            IntPtr hMod = GetModuleHandleA(dllName);
            if (hMod == IntPtr.Zero) continue; // 未加载（如延时导入），跳过

            int nameTableRva = originalFirstThunk != 0 ? originalFirstThunk : firstThunk;
            for (int t = 0; t < 8192 && findings.Count < MaxFindings; t++)
            {
                long thunkVal = Marshal.ReadIntPtr(baseAddr + nameTableRva + t * IntPtr.Size).ToInt64();
                if (thunkVal == 0) break; // 名字表结束

                IntPtr actual = Marshal.ReadIntPtr(baseAddr + firstThunk + t * IntPtr.Size);
                if (actual == IntPtr.Zero) continue;

                // 高位为序号导入标志（x64 用第 63 位、x86 用第 31 位）
                bool isOrdinal = IntPtr.Size == 8 ? thunkVal < 0 : (thunkVal & 0x8000_0000L) != 0;

                string label;
                IntPtr expected;
                if (!isOrdinal)
                {
                    string fnName = ReadAsciiZ(baseAddr + (int)thunkVal + 2, 256); // +2 跳过 hint
                    label = $"{dllName}!{fnName}";
                    expected = GetProcAddressByName(hMod, fnName);
                }
                else
                {
                    int ordinal = (int)(thunkVal & 0xFFFF);
                    label = $"{dllName}!#{ordinal}";
                    expected = GetProcAddressByOrdinal(hMod, (IntPtr)ordinal);
                }

                if (expected == IntPtr.Zero) continue; // 取不到期望地址，无法判定
                if (actual != expected)
                    findings.Add($"[IAT] {label} 表项=0x{actual.ToInt64():X}，期望=0x{expected.ToInt64():X}");
            }
        }
    }

    // ---------- Inline Hook ----------

    /// <summary>kernel32 侧探测目标：反作弊链路上最常被 Hook 的 API。</summary>
    private static readonly string[] Kernel32InlineProbes =
    {
        "GetProcAddress", "LoadLibraryA", "LoadLibraryW", "VirtualProtect",
        "VirtualAlloc", "CreateFileW", "GetModuleHandleA", "WriteProcessMemory",
    };

    /// <summary>ntdll 侧探测目标。</summary>
    private static readonly string[] NtdllInlineProbes =
    {
        "NtQueryInformationProcess", "NtCreateFile", "NtProtectVirtualMemory", "LdrLoadDll",
    };

    /// <summary>
    /// 入口字节启发式检测：读取关键函数开头的若干字节，命中典型跳转/断点指令即为可疑。
    ///
    /// <para><b>注意：这是启发式，存在误报可能。</b>本实现不做「与磁盘原始字节比对」——
    /// 因为 GetProcAddress 已自动解析前向导出，返回的是真实实现地址；而磁盘比对需自行解析
    /// 前向导出，易在 kernel32→kernelbase 转发链上产生大量误报。故仅做指令特征判定：
    /// 干净系统上这些 API 的真实实现入口为普通序言（push/mov/sub），不会命中这些特征。</para>
    /// </summary>
    private static void ScanInline(List<string> findings)
    {
        IntPtr kernel32 = GetModuleHandleA("kernel32.dll");
        IntPtr ntdll = GetModuleHandleA("ntdll.dll");

        if (kernel32 != IntPtr.Zero)
            ProbeInline(kernel32, "kernel32.dll", Kernel32InlineProbes, findings);

        if (ntdll != IntPtr.Zero)
            ProbeInline(ntdll, "ntdll.dll", NtdllInlineProbes, findings);
    }

    private static void ProbeInline(IntPtr module, string moduleLabel, string[] names, List<string> findings)
    {
        foreach (var name in names)
        {
            if (findings.Count >= MaxFindings) return;
            IntPtr addr = GetProcAddressByName(module, name);
            if (addr == IntPtr.Zero) continue;

            string? pattern = MatchHookPattern(addr);
            if (pattern != null)
                findings.Add($"[Inline] {moduleLabel}!{name} @0x{addr.ToInt64():X} 入口被改写（{pattern}）"
                             + DescribeJumpTarget(addr));
        }
    }

    /// <summary>匹配入口处的典型 Hook 指令特征；无命中返回 null。</summary>
    private static string? MatchHookPattern(IntPtr addr)
    {
        byte b0 = Marshal.ReadByte(addr);
        byte b1 = Marshal.ReadByte(addr, 1);
        if (b0 == 0xE9) return "jmp rel32 (0xE9)";
        if (b0 == 0xEB) return "jmp rel8 (0xEB)";
        if (b0 == 0xCC) return "int3 (0xCC)";
        if (b0 == 0xFF && b1 == 0x25) return "jmp [rip+disp32] (0xFF 0x25)";
        return null;
    }

    /// <summary>对 0xE9 相对跳转补出目标地址，便于判断是否跳到外部模块。</summary>
    private static string DescribeJumpTarget(IntPtr addr)
    {
        try
        {
            if (Marshal.ReadByte(addr) != 0xE9) return string.Empty;
            int rel = Marshal.ReadInt32(addr, 1);
            long target = addr.ToInt64() + 5 + rel;
            return $"，目标 0x{target:X}";
        }
        catch
        {
            return string.Empty;
        }
    }

    // ---------- EAT Hook ----------

    /// <summary>
    /// 检查已加载模块的导出表：导出函数地址应落在该模块映像范围内
    /// （前向导出的 RVA 落在导出目录区间内，属正常，跳过）。
    /// 越界的 EAT 项通常意味着导出地址被改写指向了外部映像。
    /// </summary>
    private static void ScanEat(string moduleName, List<string> findings)
    {
        IntPtr hMod = GetModuleHandleA(moduleName);
        if (hMod == IntPtr.Zero) return;

        if (!TryGetSizeOfImage(hMod, out int sizeOfImage) || sizeOfImage <= 0) return;
        if (!TryGetDataDirectory(hMod, 0, out int exportRva, out int exportSize, out _)) return;

        IntPtr exp = hMod + exportRva;
        int numberOfNames = Marshal.ReadInt32(exp, 24);
        int addressOfFunctions = Marshal.ReadInt32(exp, 28);
        int addressOfNames = Marshal.ReadInt32(exp, 32);
        int addressOfNameOrdinals = Marshal.ReadInt32(exp, 36);
        if (numberOfNames <= 0 || addressOfFunctions == 0 || addressOfNames == 0
            || addressOfNameOrdinals == 0) return;

        for (int i = 0; i < numberOfNames && findings.Count < MaxFindings; i++)
        {
            int nameRva = Marshal.ReadInt32(hMod + addressOfNames + i * 4);
            int ordinal = (ushort)Marshal.ReadInt16(hMod + addressOfNameOrdinals + i * 2);
            int funcRva = Marshal.ReadInt32(hMod + addressOfFunctions + ordinal * 4);
            if (funcRva == 0) continue;                                   // 空洞
            if (funcRva >= exportRva && funcRva < exportRva + exportSize) continue; // 前向导出，正常

            if (funcRva >= sizeOfImage)
            {
                string fnName = nameRva != 0 ? ReadAsciiZ(hMod + nameRva, 256) : ("#" + ordinal);
                findings.Add($"[EAT] {moduleName}!{fnName} 导出地址越界"
                             + $"（RVA=0x{funcRva:X}，映像大小=0x{sizeOfImage:X}）");
            }
        }
    }

    // ---------- PE 读取辅助 ----------

    /// <summary>取数据目录项（已加载映像：RVA 即可直接 base+RVA 访问）。</summary>
    private static bool TryGetDataDirectory(IntPtr moduleBase, int index, out int rva, out int size, out int optSize)
    {
        rva = size = optSize = 0;

        int e_lfanew = Marshal.ReadInt32(moduleBase, 0x3C);
        if (e_lfanew <= 0) return false;

        IntPtr nt = moduleBase + e_lfanew;
        if (Marshal.ReadInt32(nt) != 0x0000_4550) return false; // "PE\0\0"

        IntPtr fileHeader = nt + 4;
        optSize = (ushort)Marshal.ReadInt16(fileHeader, 16);
        if (optSize < 96) return false;

        IntPtr opt = fileHeader + 20;
        ushort magic = (ushort)Marshal.ReadInt16(opt);
        int dirOffset = magic == 0x20B ? 112 : 96; // PE32+ / PE32
        if (dirOffset + (index + 1) * 8 > optSize) return false;

        IntPtr dir = opt + dirOffset + index * 8;
        rva = Marshal.ReadInt32(dir);
        size = Marshal.ReadInt32(dir, 4);
        return rva != 0;
    }

    /// <summary>读 OptionalHeader.SizeOfImage（PE32 与 PE32+ 均在偏移 56）。</summary>
    private static bool TryGetSizeOfImage(IntPtr moduleBase, out int sizeOfImage)
    {
        sizeOfImage = 0;
        int e_lfanew = Marshal.ReadInt32(moduleBase, 0x3C);
        if (e_lfanew <= 0) return false;

        IntPtr nt = moduleBase + e_lfanew;
        if (Marshal.ReadInt32(nt) != 0x0000_4550) return false;

        IntPtr opt = nt + 4 + 20;
        sizeOfImage = Marshal.ReadInt32(opt, 56);
        return sizeOfImage > 0;
    }

    /// <summary>从指定地址读取以 NUL 结尾的 ASCII 字符串（最多 max 字节）。</summary>
    private static string ReadAsciiZ(IntPtr addr, int max)
    {
        var bytes = new List<byte>(max);
        for (int i = 0; i < max; i++)
        {
            byte b = Marshal.ReadByte(addr, i);
            if (b == 0) break;
            bytes.Add(b);
        }
        return Encoding.ASCII.GetString(bytes.ToArray());
    }

    /// <summary>把单个维度的异常吞掉，避免影响其它维度。</summary>
    private static void Guard(Action action)
    {
        try { action(); }
        catch { /* 该维度无法判定 */ }
    }
}