using System.Net.Http;
using System.Reflection;
using System.Security.Cryptography;
using System.Text.Json;
using System.Threading.Tasks;

namespace PaccManager.Services;

/// <summary>
/// 客户端自动更新检查：启动时拉取下载站 version.json，与本地版本比对。
/// 若远端 client_version 大于本地版本，向上层返回更新信息；安静失败（离线/解析失败不打扰用户）。
/// </summary>
public sealed class UpdateChecker
{
    private readonly HttpClient _http = new()
    {
        Timeout = System.TimeSpan.FromSeconds(8),
    };

    /// <summary>下载站上的客户端清单地址（经 dl 域静态发布）。</summary>
    public const string DefaultVersionUrl = "https://dl.potatotv.asia/files/version.json";

    /// <summary>本地程序集版本号（来自 csproj 的 &lt;Version&gt;）。</summary>
    public static string LocalVersion =>
        Assembly.GetEntryAssembly()?.GetName().Version?.ToString(3) ?? "0.0.0";

    /// <summary>
    /// 异步拉取最新版本清单。返回 null 表示获取失败（离线/解析错误静默）。
    /// 调用方自行比较 client / probe 版本。
    /// </summary>
    public async Task<ReleaseInfo?> FetchVersionAsync(string versionUrl = DefaultVersionUrl)
    {
        try
        {
            using var resp = await _http.GetAsync(versionUrl, HttpCompletionOption.ResponseHeadersRead);
            if (!resp.IsSuccessStatusCode) return null;
            var json = await resp.Content.ReadAsStringAsync();
            return JsonSerializer.Deserialize<ReleaseInfo>(json);
        }
        catch
        {
            // 离线或解析失败：静默，不影响启动
            return null;
        }
    }

    /// <summary>
    /// 下载探针 jar 到本地，回传经过 SHA256 校验后的临时文件路径（未校验或失败返回 null，不写入目标）。
    /// <paramref name="tempPath"/> 为下载/校验用临时文件；校验通过后由调用方负责移动到目标位置。
    /// </summary>
    public async Task<string?> DownloadProbeAsync(ReleaseInfo info, string tempPath)
    {
        if (info is null || string.IsNullOrEmpty(info.ProbeUrl) || string.IsNullOrEmpty(info.ProbeSha256)) return null;
        var url = info.ProbeUrl.StartsWith("/")
            ? "https://dl.potatotv.asia" + info.ProbeUrl
            : info.ProbeUrl;
        try
        {
            using var resp = await _http.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
            if (!resp.IsSuccessStatusCode) return null;
            await using (var fs = System.IO.File.Create(tempPath))
            {
                await resp.Content.CopyToAsync(fs);
            }
            var actual = BitConverter.ToString(SHA256.HashData(System.IO.File.ReadAllBytes(tempPath)))
                .Replace("-", "").ToLowerInvariant();
            return actual == info.ProbeSha256.ToLowerInvariant() ? tempPath : null;
        }
        catch
        {
            TryDelete(tempPath);
            return null;
        }
    }

    private static void TryDelete(string path)
    {
        try { if (System.IO.File.Exists(path)) System.IO.File.Delete(path); } catch { /* 忽略清理失败 */ }
    }

    /// <summary>比较两个 dot 分隔版本号：a &gt; b 返回正数，a == b 返回 0，a &lt; b 返回负数。忽略开头字母前缀（如 v4.0.0）。</summary>
    internal static int CompareVersions(string a, string b)
    {
        var aa = StripPrefix(a).Split('.');
        var bb = StripPrefix(b).Split('.');
        var n = System.Math.Max(aa.Length, bb.Length);
        for (var i = 0; i < n; i++)
        {
            var x = int.TryParse(i < aa.Length ? aa[i] : "0", out int vx) ? vx : 0;
            var y = int.TryParse(i < bb.Length ? bb[i] : "0", out int vy) ? vy : 0;
            if (x != y) return x - y;
        }
        return 0;
    }

    /// <summary>去掉版本号开头的字母前缀（如 "v4.0.0" -&gt; "4.0.0"）。</summary>
    private static string StripPrefix(string s)
    {
        var v = s.Trim();
        var i = 0;
        while (i < v.Length && !char.IsDigit(v[i])) i++;
        return v.Substring(i);
    }
}

/// <summary>下载站 version.json 中与客户端相关的字段。</summary>
public sealed class ReleaseInfo
{
    public string? ClientVersion { get; set; }
    public string? ClientUrl { get; set; }
    public string? ClientSha256 { get; set; }
    public string? ProbeVersion { get; set; }
    public string? ProbeUrl { get; set; }
    public string? ProbeSha256 { get; set; }
}