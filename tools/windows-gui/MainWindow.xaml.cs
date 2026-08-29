using System.Text;
using System.Windows;
using System.Windows.Controls;
using PaccManager.Services;

namespace PaccManager;

public partial class MainWindow : Window
{
    private readonly ConfigManager _config = new();

    public MainWindow()
    {
        InitializeComponent();
        ConfigPathText.Text = _config.FilePath;
        Loaded += (_, _) => OnLoadConfig(); // 启动即加载现有配置
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
            _config.Set("pacc.client.endpoint", _config.Get("pacc.client.endpoint") ?? "wss://pacc.potatotv.asia/ws/ptv");
            _config.Set("pacc.client.api-base", _config.Get("pacc.client.api-base") ?? "https://api.potatotv.asia");
            _config.Set("pacc.detection.redscreen-threshold", _config.Get("pacc.detection.redscreen-threshold") ?? "85");
            _config.Set("pacc.detection.sample-rate", _config.Get("pacc.detection.sample-rate") ?? "1.0");
            _config.Set("pacc.log.level", _config.Get("pacc.log.level") ?? "INFO");
            _config.Save();

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

    // ---------- 配置页 ----------
    private void OnLoadConfig(object sender, RoutedEventArgs e)
    {
        CfgEndpoint.Text = _config.Get("pacc.client.endpoint") ?? "wss://pacc.potatotv.asia/ws/ptv";
        CfgApiBase.Text = _config.Get("pacc.client.api-base") ?? "https://api.potatotv.asia";
        CfgThreshold.Text = _config.Get("pacc.detection.redscreen-threshold") ?? "85";
        CfgSampleRate.Text = _config.Get("pacc.detection.sample-rate") ?? "1.0";
        CfgLogLevel.Text = _config.Get("pacc.log.level") ?? "INFO";
        CfgPteid.Text = _config.Get("pacc.client.pteid") ?? "";
        ConfigStatus.Text = "已加载: " + (_config.Load() ? "存在 " : "默认（文件不存在，保存后生成）");
    }

    private void OnSaveConfig(object sender, RoutedEventArgs e)
    {
        if (!Valid("红屏阈值", CfgThreshold.Text)) return;
        if (!Valid("采样率", CfgSampleRate.Text)) return;
        if (string.IsNullOrWhiteSpace(CfgLogLevel.Text))
        {
            ConfigStatus.Text = "日志级别不能为空";
            return;
        }
        _config.Set("pacc.client.endpoint", CfgEndpoint.Text.Trim());
        _config.Set("pacc.client.api-base", CfgApiBase.Text.Trim());
        _config.Set("pacc.detection.redscreen-threshold", CfgThreshold.Text.Trim());
        _config.Set("pacc.detection.sample-rate", CfgSampleRate.Text.Trim());
        _config.Set("pacc.log.level", CfgLogLevel.Text.Trim().ToUpperInvariant());
        _config.Set("pacc.client.pteid", CfgPteid.Text.Trim());
        try
        {
            _config.Save();
            StatusText.Text = "配置已保存";
            ConfigStatus.Text = "已保存到: " + _config.FilePath;
        }
        catch (Exception ex)
        {
            ConfigStatus.Text = "保存失败: " + ex.Message;
        }
    }

    private bool Valid(string label, string raw)
    {
        var (ok, msg) = ConfigManager.Validate(label.IfNumericKey(), raw);
        if (!ok) ConfigStatus.Text = msg;
        return ok;
    }

    private async void OnTestConfig(object sender, RoutedEventArgs e)
    {
        StatusText.Text = "测试中…";
        var endpoint = CfgEndpoint.Text.Trim();
        var api = CfgApiBase.Text.Trim();
        var sb = new StringBuilder();
        sb.AppendLine($"WSS 入口: {endpoint}");
        var (ms1, d1) = await Diagnostics.ProbeHttpsAsync(ToHttps(endpoint));
        sb.AppendLine($"  {{\"ms\":{ms1},\"result\":\"{d1}\"}}");
        var (ms2, d2) = await Diagnostics.ProbeHttpsAsync(ToHttps(api) + "/api/health");
        sb.AppendLine($"REST API: {api}/api/health -> {d2} ({ms2} ms)");
        EndpointProbe.Text = d1;
        ApiBaseProbe.Text = d2;
        InstallCheckBox.Text = sb.ToString();
        StatusText.Text = "测试完成";
    }

    private static string ToHttps(string s) =>
        s.StartsWith("http", StringComparison.OrdinalIgnoreCase) ? s : "https://" + s;

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

internal static class NumKey
{
    /// <summary>将 UI 中文标签映射为半自动校验键（用于区间校验）。</summary>
    public static string IfNumericKey(this string label)
    {
        return label switch
        {
            "红屏阈值" => "redscreen-threshold",
            "采样率" => "sample-rate",
            _ => label,
        };
    }
}