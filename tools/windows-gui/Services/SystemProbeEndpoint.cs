using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;

namespace PaccManager.Services;

/// <summary>
/// 回环系统探针端点（文档 §4.3 / §5.3）：在 {@code 127.0.0.1:17020} 上暴露最小 HTTP 接口，
/// 供 Java 探针取回 .NET 原生能力（模块枚举、用户态内存扫描）。
///
/// <para>契约与 {@code ptv-client} 的 {@code PaccProbeClient} 一一对应：</para>
/// <list type="bullet">
///   <item>{@code GET /probe/health} → {@code {"ok":true}}</item>
///   <item>{@code GET /probe/modules?process=} → {@code {"process":"...","modules":[{"name","path","base","size"}]}}</item>
///   <item>{@code GET /probe/memory?process=&signature=&pattern=hex&mask=hex} →
///         {@code {"signature":"...","supported":true,"addresses":[...],"note":"..."}}</item>
///   <item>{@code GET /probe/signature?process=} →
///         {@code {"process":"...","supported":true,"modules":[{"path":"...","valid":false,"publisher":null,"note":null}],"note":null}}</item>
///   <item>{@code GET /probe/verify?paths=p1;p2;...} →
///         {@code {"supported":true,"files":[{"path":"...","valid":true,"publisher":"...","note":null}],"note":null}}</item>
///   <item>{@code GET /probe/injection?process=} →
///         {@code {"process":"...","supported":true,"remoteThreadCount":0,"execRwRegionCount":0,"pendingApc":false,"suspiciousHandleCount":0,"note":"..."}}</item>
/// </list>
///
/// <para>安全约束：只绑定 {@link IPAddress#Loopback}（外部不可达，不做跨网暴露）；只接受 {@code GET}；
/// 只服务 {@code /probe/} 前缀；单行请求行长度有上限；任何处理异常都返回可用性最低的合法响应，
/// 绝不让端点线程崩溃。用 {@link TcpListener} 而非 {@code HttpListener}，是为了避免 http.sys
/// 的 URL ACL 在非管理员身份下注册失败。</para>
/// </summary>
public sealed class SystemProbeEndpoint : IDisposable
{
    private const int MaxRequestLine = 4096;
    private const int MaxQueryLength = 2048;
    private const int MaxModules = 4096;
    private const int MaxVerifyPaths = 64;

    private readonly int _port;
    private TcpListener? _listener;
    private Thread? _thread;
    private volatile bool _running;

    public SystemProbeEndpoint(int port = 17020)
    {
        _port = port;
    }

    /// <summary>是否正在监听。</summary>
    public bool Running => _running;

    /// <summary>启动监听；端口被占用或权限不足时返回 false（调用方据此静默降级）。</summary>
    public bool Start()
    {
        if (_running) return true;
        try
        {
            var listener = new TcpListener(IPAddress.Loopback, _port);
            listener.Start();
            _listener = listener;
            _running = true;
            _thread = new Thread(Loop) { IsBackground = true, Name = "pacc-probe" };
            _thread.Start();
            return true;
        }
        catch
        {
            _running = false;
            _listener = null;
            return false;
        }
    }

    /// <summary>停止监听并释放端口。</summary>
    public void Stop()
    {
        _running = false;
        try { _listener?.Stop(); } catch { /* 已停 */ }
        _listener = null;
    }

    public void Dispose() => Stop();

    // ---------- 监听循环 ----------

    private void Loop()
    {
        while (_running)
        {
            TcpClient client;
            try { client = _listener!.AcceptTcpClient(); }
            catch { break; } // 监听器被停止

            ThreadPool.QueueUserWorkItem(_ => Handle(client));
        }
    }

    private void Handle(TcpClient client)
    {
        try
        {
            // 读写超时：连接后不发数据的客户端不能让线程池线程永久阻塞
            client.ReceiveTimeout = 3000;
            client.SendTimeout = 3000;
            using (client)
            using (var stream = client.GetStream())
            {
                string requestLine = ReadRequestLine(stream);
                string method = string.Empty;
                string path = string.Empty;
                ParseRequestLine(requestLine, ref method, ref path);

                if (!method.Equals("GET", StringComparison.OrdinalIgnoreCase))
                {
                    WriteJson(stream, 405, new { error = "method_not_allowed" });
                    return;
                }
                if (!path.StartsWith("/probe", StringComparison.Ordinal))
                {
                    WriteJson(stream, 404, new { error = "not_found" });
                    return;
                }

                var (route, query) = SplitPath(path);
                switch (route)
                {
                    case "/probe/health":
                        WriteJson(stream, 200, new { ok = true });
                        break;
                    case "/probe/modules":
                        WriteJson(stream, 200, ModulesResponse(query.GetValueOrDefault("process", string.Empty)));
                        break;
                    case "/probe/memory":
                        WriteJson(stream, 200, MemoryResponse(query));
                        break;
                    case "/probe/signature":
                        WriteJson(stream, 200, SignatureResponse(query.GetValueOrDefault("process", string.Empty)));
                        break;
                    case "/probe/verify":
                        WriteJson(stream, 200, VerifyResponse(query));
                        break;
                    case "/probe/injection":
                        WriteJson(stream, 200, InjectionResponse(query.GetValueOrDefault("process", string.Empty)));
                        break;
                    default:
                        WriteJson(stream, 404, new { error = "not_found" });
                        break;
                }
            }
        }
        catch
        {
            // 单连接异常不影响监听循环
        }
    }

    // ---------- 业务 ----------

    private static object ModulesResponse(string processName)
    {
        var modules = new List<object>();
        if (!string.IsNullOrWhiteSpace(processName))
        {
            string name = Path.GetFileNameWithoutExtension(processName);
            try
            {
                var procs = System.Diagnostics.Process.GetProcessesByName(name);
                if (procs.Length > 0)
                {
                    using var proc = procs[0];
                    for (int i = 1; i < procs.Length; i++) procs[i].Dispose();
                    int count = 0;
                    foreach (System.Diagnostics.ProcessModule module in proc.Modules)
                    {
                        if (count++ >= MaxModules) break;
                        modules.Add(new
                        {
                            name = module.ModuleName,
                            path = SafeFileName(module),
                            @base = module.BaseAddress.ToInt64(),
                            size = module.ModuleMemorySize
                        });
                    }
                }
            }
            catch
            {
                // 权限不足 / 进程退出：返回空模块列表
            }
        }
        return new { process = processName, modules };
    }

    private static object MemoryResponse(Dictionary<string, string> query)
    {
        string signature = query.GetValueOrDefault("signature", string.Empty);
        string process = query.GetValueOrDefault("process", string.Empty);
        byte[] pattern = FromHex(query.GetValueOrDefault("pattern", string.Empty));
        byte[] mask = FromHex(query.GetValueOrDefault("mask", string.Empty));
        if (pattern.Length == 0 || pattern.Length != mask.Length)
        {
            return new { signature, supported = false, addresses = Array.Empty<long>(), note = "特征码或掩码非法" };
        }

        var result = MemoryScanner.Scan(process, pattern, mask);
        return new
        {
            signature,
            supported = result.Supported,
            addresses = result.Addresses,
            note = result.Note
        };
    }

    private static object SignatureResponse(string processName)
    {
        bool supported = ProcessExists(processName);
        var modules = new List<object>();
        if (supported)
        {
            foreach (var info in DllSignatureVerifier.VerifyModules(processName))
            {
                modules.Add(new
                {
                    path = info.Path,
                    valid = info.Valid,
                    publisher = info.Publisher,
                    note = info.Note
                });
            }
        }
        return new { process = processName, supported, modules, note = (string?)null };
    }

    private static object VerifyResponse(Dictionary<string, string> query)
    {
        string raw = query.GetValueOrDefault("paths", string.Empty);
        string[] paths = raw.Split(';', StringSplitOptions.RemoveEmptyEntries);
        if (paths.Length > MaxVerifyPaths) paths = paths[..MaxVerifyPaths];

        var files = new List<object>();
        foreach (var info in DllSignatureVerifier.VerifyFiles(paths))
        {
            files.Add(new
            {
                path = info.Path,
                valid = info.Valid,
                publisher = info.Publisher,
                note = info.Note
            });
        }
        return new { supported = true, files, note = (string?)null };
    }

    private static object InjectionResponse(string processName)
    {
        var result = InjectionDetector.Detect(processName);
        return new
        {
            process = processName,
            supported = result.Supported,
            remoteThreadCount = result.RemoteThreadCount,
            execRwRegionCount = result.ExecRwRegionCount,
            pendingApc = result.PendingApc,
            suspiciousHandleCount = result.SuspiciousHandleCount,
            note = result.Note
        };
    }

    /// <summary>指定进程是否存在（supported 判定用）。</summary>
    private static bool ProcessExists(string processName)
    {
        if (string.IsNullOrWhiteSpace(processName)) return false;
        try
        {
            var procs = System.Diagnostics.Process.GetProcessesByName(Path.GetFileNameWithoutExtension(processName));
            bool any = procs.Length > 0;
            foreach (var proc in procs) proc.Dispose();
            return any;
        }
        catch
        {
            return false;
        }
    }

    private static string SafeFileName(System.Diagnostics.ProcessModule module)
    {
        try { return module.FileName ?? string.Empty; }
        catch { return string.Empty; }
    }

    // ---------- HTTP 解析 ----------

    private static string ReadRequestLine(NetworkStream stream)
    {
        var sb = new StringBuilder(128);
        var one = new byte[1];
        while (sb.Length < MaxRequestLine)
        {
            int n = stream.Read(one, 0, 1);
            if (n <= 0) break;
            char c = (char)one[0];
            if (c == '\n') break;
            if (c != '\r') sb.Append(c);
        }
        return sb.ToString();
    }

    private static void ParseRequestLine(string line, ref string method, ref string path)
    {
        var parts = line.Split(' ');
        if (parts.Length >= 2)
        {
            method = parts[0];
            path = parts[1];
        }
    }

    private static (string Route, Dictionary<string, string> Query) SplitPath(string path)
    {
        int q = path.IndexOf('?');
        if (q < 0) return (path, new Dictionary<string, string>());
        string route = path[..q];
        string raw = path[(q + 1)..];
        if (raw.Length > MaxQueryLength) raw = raw[..MaxQueryLength];

        var query = new Dictionary<string, string>();
        foreach (var pair in raw.Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            int eq = pair.IndexOf('=');
            if (eq <= 0) continue;
            string key = Uri.UnescapeDataString(pair[..eq]);
            string value = Uri.UnescapeDataString(pair[(eq + 1)..]);
            query[key] = value;
        }
        return (route, query);
    }

    private static byte[] FromHex(string hex)
    {
        if (string.IsNullOrEmpty(hex) || (hex.Length & 1) != 0) return Array.Empty<byte>();
        var bytes = new byte[hex.Length / 2];
        for (int i = 0; i < bytes.Length; i++)
        {
            int hi = HexValue(hex[i * 2]);
            int lo = HexValue(hex[i * 2 + 1]);
            if (hi < 0 || lo < 0) return Array.Empty<byte>();
            bytes[i] = (byte)((hi << 4) | lo);
        }
        return bytes;
    }

    private static int HexValue(char c) => c switch
    {
        >= '0' and <= '9' => c - '0',
        >= 'a' and <= 'f' => c - 'a' + 10,
        >= 'A' and <= 'F' => c - 'A' + 10,
        _ => -1
    };

    private static void WriteJson(NetworkStream stream, int status, object payload)
    {
        byte[] body = JsonSerializer.SerializeToUtf8Bytes(payload);
        string head = $"HTTP/1.1 {status} {(status == 200 ? "OK" : "Error")}\r\n"
                      + "Content-Type: application/json; charset=utf-8\r\n"
                      + $"Content-Length: {body.Length}\r\n"
                      + "Connection: close\r\n\r\n";
        byte[] headBytes = Encoding.ASCII.GetBytes(head);
        stream.Write(headBytes, 0, headBytes.Length);
        stream.Write(body, 0, body.Length);
        stream.Flush();
    }
}