using System.Net;
using System.Net.Http;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;

namespace PaccManager.Services;

/// <summary>
/// 诊断工具：连通性探测、HTTPS 端点测温、本机网络/权限基线。
/// </summary>
public static class Diagnostics
{
    public static bool IsAdministrator()
    {
        try
        {
            using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
            var principal = new System.Security.Principal.WindowsPrincipal(identity);
            return principal.IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
        }
        catch
        {
            return false;
        }
    }

    public static string HostInfo()
    {
        var arch = RuntimeInformation.ProcessArchitecture switch
        {
            Architecture.X64 => "x86-64",
            Architecture.Arm64 => "ARM64",
            _ => RuntimeInformation.ProcessArchitecture.ToString(),
        };
        return $"OS: {Environment.OSVersion.VersionString} | 架构: {arch} | " +
               $"CPU: {Environment.ProcessorCount} 核 | " +
               $"管理员: {(IsAdministrator() ? "是" : "否")}";
    }

    public static string NetworkSummary()
    {
        var sb = new StringBuilder();
        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback
                or NetworkInterfaceType.Tunnel) continue;
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            foreach (var unicast in ni.GetIPProperties().UnicastAddresses)
            {
                if (unicast.Address.AddressFamily == AddressFamily.InterNetwork)
                {
                    sb.AppendLine($"  {ni.Name}: {unicast.Address}  ({(int)ni.Speed / 1_000_000} Mbps)");
                }
            }
        }
        return sb.Length == 0 ? "  未检测到活动网卡。" : sb.ToString().TrimEnd();
    }

    /// <summary>对指定 HTTPS 端点做一次测温，返回耗时毫秒或错误。</summary>
    public static async Task<(long ms, string detail)> ProbeHttpsAsync(string url, string? token = null)
    {
        using var client = new HttpClient
        {
            Timeout = TimeSpan.FromSeconds(10),
            DefaultRequestVersion = HttpVersion.Version11,
        };
        if (!string.IsNullOrEmpty(token))
            client.DefaultRequestHeaders.Authorization = new("Bearer", token);
        var sw = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            using var resp = await client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead);
            sw.Stop();
            return (sw.ElapsedMilliseconds,
                $"{(int)resp.StatusCode} ({(resp.Content.Headers.ContentLength ?? 0) / 1024} KB, {sw.ElapsedMilliseconds} ms)");
        }
        catch (HttpRequestException ex)
        {
            sw.Stop();
            return (sw.ElapsedMilliseconds, $"错误: {ex.Message}");
        }
        catch (TaskCanceledException)
        {
            return (sw.ElapsedMilliseconds, "超时（>10s）");
        }
    }

    /// <summary>TCP 端口连通性快速测试。</summary>
    public static (bool ok, long ms) TcpProbe(string host, int port)
    {
        using var client = new TcpClient();
        var sw = System.Diagnostics.Stopwatch.StartNew();
        try
        {
            var task = client.ConnectAsync(host, port);
            if (!task.Wait(TimeSpan.FromSeconds(5)))
            {
                sw.Stop();
                return (false, -1);
            }
            sw.Stop();
            return (client.Connected, sw.ElapsedMilliseconds);
        }
        catch
        {
            sw.Stop();
            return (false, sw.ElapsedMilliseconds);
        }
    }
}