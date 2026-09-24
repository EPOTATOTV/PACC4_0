using System.Text;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using PaccManager.Services;

namespace PaccManager;

public partial class MainWindow : Window
{
    private readonly ConfigManager _config = new();

    private readonly UpdateChecker _updater = new();

    public MainWindow()
    {
        InitializeComponent();
        // 窗口渲染后异步检查更新，不阻塞启动
        ContentRendered += OnContentRendered;
    }

    private async void OnContentRendered(object? sender, EventArgs e)
    {
        // 本地完整性校验放在联网之前：离线也要能发现探针被换过
        VerifyProbeIntegrity();

        var info = await _updater.FetchVersionAsync();
        if (info is null) return; // 离线或解析失败，静默

        // 1) 客户端自身更新：有新版则引导前往下载页
        if (!string.IsNullOrEmpty(info.ClientVersion)
            && UpdateChecker.CompareVersions(info.ClientVersion, UpdateChecker.LocalVersion) > 0)
        {
            var downloadUrl = info.ClientUrl?.StartsWith("/") == true
                ? "https://dl.potatotv.asia" + info.ClientUrl
                : info.ClientUrl;
            var result = MessageBox.Show(this,
                $"发现新版本 {info.ClientVersion}（当前 {UpdateChecker.LocalVersion}）。\n\n" +
                "是否前往下载页获取新版？", "PACC 更新可用",
                MessageBoxButton.YesNo, MessageBoxImage.Information);
            if (result == MessageBoxResult.Yes && !string.IsNullOrEmpty(downloadUrl))
            {
                try { System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(downloadUrl) { UseShellExecute = true }); }
                catch { StatusText.Text = "无法打开下载链接，请手动访问 dl.potatotv.asia"; }
            }
        }

        // 2) 探针更新：由本管理器统一下载校验后替换，避免 jar 运行中自我覆盖被锁
        await UpdateProbeSilentlyAsync(info);
    }

    private async Task UpdateProbeSilentlyAsync(ReleaseInfo info)
    {
        if (string.IsNullOrEmpty(info.ProbeVersion) || string.IsNullOrEmpty(info.ProbeUrl) || string.IsNullOrEmpty(info.ProbeSha256))
            return;

        string appDir = InstallDir ?? AppContext.BaseDirectory;
        string binDir = System.IO.Path.Combine(appDir, "bin");
        string probeFile = System.IO.Path.Combine(binDir, "ptv-agent-5.0.0.jar");
        string versionFile = System.IO.Path.Combine(binDir, "probe.version");
        string sealFile = probeFile + ProbeIntegrity.SealSuffix;

        // 已有同版本探针则不重复下载；但若封存摘要缺失（从旧版升级上来），补封一次
        try
        {
            if (System.IO.File.Exists(versionFile) && System.IO.File.ReadAllText(versionFile).Trim() == info.ProbeVersion)
            {
                if (!System.IO.File.Exists(sealFile)) ProbeIntegrity.Seal(probeFile, sealFile);
                return;
            }
        }
        catch { /* 忽略读取失败，继续更新 */ }

        string tempPath = probeFile + ".download";
        try
        {
            var ok = await _updater.DownloadProbeAsync(info, tempPath);
            if (string.IsNullOrEmpty(ok)) return; // 下载/校验失败，静默保留旧版
            if (!System.IO.Directory.Exists(binDir)) System.IO.Directory.CreateDirectory(binDir);
            System.IO.File.Copy(ok, probeFile, true);
            System.IO.File.WriteAllText(versionFile, info.ProbeVersion);
            // 落盘后立刻封存摘要：这是「已验证的 jar」与「以后启动时比对的基准」之间唯一的衔接点
            ProbeIntegrity.Seal(probeFile, sealFile);
            StatusText.Text = $"探针已更新至 {info.ProbeVersion}（重启 PTV 服务后生效）";
        }
        catch { /* 更新失败不影响主程序 */ }
        finally
        {
            try { if (System.IO.File.Exists(tempPath)) System.IO.File.Delete(tempPath); } catch { /* 清理失败忽略 */ }
        }
    }

    /// <summary>
    /// 启动时核对本地探针 jar 是否仍是安装时的那一份。
    /// 只在「确定不一致」时告警：解不开封存（换机器/换用户）归为无法判定，不打扰用户。
    /// </summary>
    private void VerifyProbeIntegrity()
    {
        try
        {
            string binDir = System.IO.Path.Combine(InstallDir ?? AppContext.BaseDirectory, "bin");
            string probeFile = System.IO.Path.Combine(binDir, "ptv-agent-5.0.0.jar");
            if (ProbeIntegrity.Verify(probeFile, probeFile + ProbeIntegrity.SealSuffix)
                != ProbeIntegrity.State.Mismatch) return;

            StatusText.Text = "警告：本地探针文件与安装时不一致，检测可能已失效";
            MessageBox.Show(this,
                "本地探针文件（bin\\ptv-agent-5.0.0.jar）与安装时记录的摘要不一致。\n\n" +
                "这通常意味着文件被替换或修改，检测结果不再可信。\n" +
                "建议从 dl.potatotv.asia 重新安装客户端。",
                "PACC 完整性告警", MessageBoxButton.OK, MessageBoxImage.Warning);
        }
        catch { /* 校验本身绝不影响启动 */ }
    }

    /// <summary>读取安装目录（install.iss 写入 HKCU 注册表），无则回退当前运行目录。</summary>
    private static string? InstallDir
    {
        get
        {
            try
            {
                using var key = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(
                    @"Software\PotatoTV\PACC 客户端");
                return key?.GetValue("InstallPath") as string;
            }
            catch { return null; }
        }
    }

    // ---------- 安装页 ----------
    private async void OnRunInstallCheck(object sender, RoutedEventArgs e)
    {
        var sb = new StringBuilder();
        sb.AppendLine(Diagnostics.HostInfo());
        sb.AppendLine();
        sb.AppendLine("网络基线:");
        sb.AppendLine(Diagnostics.NetworkSummary());
        sb.AppendLine();
        sb.AppendLine("关键端点测温:");
        foreach (var host in new[] { "api.potatotv.asia", "pacc.potatotv.asia", "dl.potatotv.asia" })
        {
            var url = $"https://{host}/";
            var (ms, detail) = await Diagnostics.ProbeHttpsAsync(url);
            sb.AppendLine($"  {host}: {detail}");
        }
        InstallCheckBox.Text = sb.ToString();
        StatusText.Text = "环境自检完成";
    }

    private void OnInstallPacc(object sender, RoutedEventArgs e)
    {
        if (!Diagnostics.IsAdministrator())
        {
            MessageBox.Show(this, "需要以管理员身份运行才能安装 PACC 服务。", "权限不足",
                MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }
        try
        {
            _config.Load();
            // 补齐默认值，确保安装后即可运行
            _config.Set("pacc.client.wss.uri", _config.Get("pacc.client.wss.uri") ?? "wss://pacc.potatotv.asia/ws/ptv");
            _config.Set("pacc.client.server.uri", _config.Get("pacc.client.server.uri") ?? "https://api.potatotv.asia");
            _config.Set("pacc.detection.redscreen-threshold", _config.Get("pacc.detection.redscreen-threshold") ?? "85");
            _config.Set("pacc.detection.sample-rate", _config.Get("pacc.detection.sample-rate") ?? "1.0");
            _config.Set("pacc.log.level", _config.Get("pacc.log.level") ?? "INFO");
            _config.Save();
            // 首次运行将敏感键（wss/sig 密钥、token）原地加密，非 "enc:" 则幂等跳过
            _config.EncryptSensitiveInPlace();

            // TODO: 在此调用底层安装器（内核驱动 / 服务注册）。
            // 由 tools/windows-gui/deploy/installer.ps1 实现真实安装逻辑。
            StatusText.Text = "配置已生成。请运行 deploy/installer.ps1 完成驱动的安装注册。";
            InstallCheckBox.Text += Environment.NewLine +
                Environment.NewLine + "[安装] 配置文件已写入: " + _config.FilePath;
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "安装失败", MessageBoxButton.OK, MessageBoxImage.Error);
        }
    }

    // ---------- 诊断页 ----------
    private async void OnDiagNetwork(object sender, RoutedEventArgs e)
    {
        StatusText.Text = "诊断中…";
        var sb = new StringBuilder();
        sb.AppendLine("=== PACC 诊断报告 ===");
        sb.AppendLine(Diagnostics.HostInfo());
        sb.AppendLine();
        sb.AppendLine("网络接口:");
        sb.AppendLine(Diagnostics.NetworkSummary());
        sb.AppendLine();
        sb.AppendLine("关键端点测温:");
        foreach (var host in new[] { "api.potatotv.asia", "pacc.potatotv.asia", "dl.potatotv.asia" })
        {
            var (_, detail) = await Diagnostics.ProbeHttpsAsync($"https://{host}/");
            sb.AppendLine($"  {host}: {detail}");
        }
        sb.AppendLine();
        sb.AppendLine("配置状态: " + (_config.Load() ? "已加载 (" + _config.Values.Count + " 项)" : "未找到配置"));
        DiagnosticBox.Text = sb.ToString();
        StatusText.Text = "诊断完成";
    }

    private void OnCopyReport(object sender, RoutedEventArgs e)
    {
        Clipboard.SetText(DiagnosticBox.Text);
        StatusText.Text = "报告已复制到剪贴板";
    }
}