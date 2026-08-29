using System.Text;

namespace PaccManager.Services;

/// <summary>
/// PACC 客户端配置管理：读写 pacc-client.properties（key=value）。
/// 覆盖：PACC_CLIENT_* 域名绑定、红屏阈值、上报采样率、日志级别等。
/// </summary>
public sealed class ConfigManager
{
    public const string DefaultFileName = "pacc-client.properties";

    /// <summary>配置路径；可被外部指定（便于测试/便携模式）。</summary>
    public string FilePath { get; }

    private readonly Dictionary<string, string> _values = new(StringComparer.OrdinalIgnoreCase);

    public IReadOnlyDictionary<string, string> Values => _values;

    private static readonly string[] KnownKeys =
    {
        "pacc.client.endpoint",      // 玩家长连接 / API 入口（pacc.potatotv.asia）
        "pacc.client.api-base",      // REST API 基址（api.potatotv.asia）
        "pacc.client.pteid",         // 自动登录 PTEID
        "pacc.client.token",         // 自动登录临时 token（不落盘明文，需勾选才写入）
        "pacc.detection.redscreen-threshold", // 红屏触发风险阈值（0-100）
        "pacc.detection.sample-rate",         // 检测上报采样率（0-1）
        "pacc.log.level",                     // TRACE/DEBUG/INFO/WARN/ERROR
        "pacc.log.max-size-mb",
    };

    public ConfigManager() : this(Path.Combine(BaseDir(), DefaultFileName)) { }

    public ConfigManager(string path) => FilePath = path;

    private static string BaseDir()
    {
        // 便携模式：与安装目录同目录；否则用 %ProgramData%\PACC
        var exeDir = AppContext.BaseDirectory;
        var probe = Path.Combine(exeDir, DefaultFileName);
        return File.Exists(probe) ? exeDir : Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "PACC");
    }

    /// <summary>加载；文件不存在返回 false。</summary>
    public bool Load()
    {
        _values.Clear();
        if (!File.Exists(FilePath)) return false;
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        foreach (var raw in File.ReadAllLines(FilePath, Encoding.UTF8))
        {
            var line = raw.Trim();
            if (line.Length == 0 || line.StartsWith('#') || line.StartsWith(';')) continue;
            var idx = line.IndexOf('=');
            if (idx <= 0) continue;
            var k = line[..idx].Trim();
            var v = line[(idx + 1)..].Trim();
            if (k.Length == 0) continue;
            _values[k] = v;
        }
        return _values.Count > 0;
    }

    public string? Get(string key) => _values.TryGetValue(key, out var v) ? v : null;

    public void Set(string key, string value) => _values[key] = value;

    /// <summary>以稳定的 key 顺序回写文件（含已知键注释头）。</summary>
    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(FilePath)!);
        var sb = new StringBuilder();
        sb.AppendLine("# PACC v4.0 客户端配置（由 PaccManager 维护，勿手工删除此行）");
        sb.AppendLine("# 域名配置支持环境变量覆盖：PACC_CLIENT_* 优先于本文件");
        sb.AppendLine();
        foreach (var k in Values.OrderBy(p => Array.IndexOf(KnownKeys, p.Key), Comparer<int>.Default)
                                .ThenBy(p => p.Key, StringComparer.OrdinalIgnoreCase))
        {
            sb.AppendLine($"{k.Key}={k.Value}");
        }
        File.WriteAllText(FilePath, sb.ToString(), new UTF8Encoding(false));
    }

    /// <summary>校验阈值类键值在合法区间。</summary>
    public static (bool ok, string msg) Validate(string key, string raw)
    {
        if (!double.TryParse(raw, out var d)) return (false, $"「{key}」需为数字，收到：{raw}");
        if (key.Contains("redscreen") && (d < 0 || d > 100)) return (false, "红屏阈值需在 0-100 之间");
        if (key.Contains("sample-rate") && (d < 0 || d > 1)) return (false, "采样率需在 0-1 之间");
        return (true, "OK");
    }
}