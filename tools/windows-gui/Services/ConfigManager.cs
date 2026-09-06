using System.IO;
using System.Linq;
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
        "pacc.client.wss.uri",           // 玩家长连接 WSS 端点（pacc.potatotv.asia/ws/ptv）
        "pacc.client.server.uri",        // REST API 基址（api.potatotv.asia）
        "pacc.client.pteid",             // 自动登录 PTEID（明文保留，WSS 握手使用）
        "pacc.client.token",             // 自动登录临时 token（敏感，加密落盘）
        "pacc.client.wss.sign.secret",   // WSS 上报签名密钥（敏感，加密落盘）
        "pacc.client.signature.secret",  // 特征库包签名密钥（敏感，加密落盘）
        "pacc.detection.redscreen-threshold", // 红屏触发风险阈值（0-100）
        "pacc.detection.sample-rate",         // 检测上报采样率（0-1）
        "pacc.log.level",                     // TRACE/DEBUG/INFO/WARN/ERROR
        "pacc.log.max-size-mb",
    };

    /// <summary>须加密落盘的敏感键；pteid 不在其中（本就在 WSS URI 公开，且为关键派生因子）。</summary>
    public static readonly string[] SensitiveKeys =
    {
        "pacc.client.wss.sign.secret",
        "pacc.client.signature.secret",
        "pacc.client.token",
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

    /// <summary>
    /// 读取配置值并解密密文：存值带 "enc:" 前缀时用本机指纹解密；否则原样返回（兼容明文/未加密）。
    /// 解密失败（机器变化/被篡改）返回 null，不抛异常。
    /// </summary>
    public string? GetDecrypted(string key)
    {
        var v = Get(key);
        if (string.IsNullOrEmpty(v)) return null;
        if (!v.StartsWith(ConfigCrypt.Prefix, StringComparison.Ordinal))
            return v;
        return ConfigCrypt.TryDecrypt(v, ConfigCrypt.MachineFingerprint()) ?? null;
    }

    /// <summary>
    /// 把非空的敏感键（wss/sig 密钥、token）原地加密落盘。幂等：已是 "enc:" 的跳过。
    /// 返回是否有变动；调用方需在 Load() 之后、Save() 前后按需调用。
    /// </summary>
    public bool EncryptSensitiveInPlace()
    {
        bool changed = false;
        var password = ConfigCrypt.MachineFingerprint();
        foreach (var key in SensitiveKeys)
        {
            var v = Get(key);
            if (string.IsNullOrEmpty(v) || v.StartsWith(ConfigCrypt.Prefix, StringComparison.Ordinal))
                continue;
            Set(key, ConfigCrypt.Encrypt(v, password));
            changed = true;
        }
        if (changed) Save();
        return changed;
    }

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