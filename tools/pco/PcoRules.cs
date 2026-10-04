using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace PaccManager.Pco;

/// <summary>
/// 反调试 / 完整性校验命中后的处置动作。
///
/// <para><b>默认 Degrade。</b>开发工具、沙箱、部分杀软都会让调试器标志为真，
/// 硬退会把正常用户挡在门外，所以缺省只置位、不退出；Exit 是显式选择。</para>
/// </summary>
internal enum DetectionAction
{
    /// <summary>只置检测标志，进程继续跑（默认）。</summary>
    Degrade,

    /// <summary>命中即结束进程。</summary>
    Exit,

    /// <summary>只置标志，交给宿主自行上报。</summary>
    Report,
}

/// <summary>
/// PCO 规则文件（pco-rules.json）的解析与匹配。
///
/// <para>形态与设计文档一致：<c>rules.keep</c> 是保留清单，<c>options</c> 是行为开关。
/// keep 条目支持三种写法：完整类型名（保类型与它的全部成员）、<c>类型::成员名</c>（只保该成员）、
/// 以及用 <c>*</c> 通配的段（如 <c>PaccManager.Views.*</c>、<c>*::.ctor</c>）。</para>
/// </summary>
internal sealed class PcoRules
{
    private readonly List<Regex> _keepTypes = [];
    private readonly List<(Regex Type, Regex Member)> _keepMembers = [];

    /// <summary>重命名命名空间（拍平）。关掉的话只改类名与成员名，命名空间原样保留。</summary>
    public bool RenameNamespaces { get; private set; } = true;

    // ---- IL 变换开关。默认全关：不写进规则文件就只做重命名，出问题逐个关掉即可回退。 ----

    /// <summary>字符串加密。</summary>
    public bool StringEncrypt { get; private set; }

    /// <summary>字符串加密密钥；规则文件里没写就取每次运行随机的非零字节。</summary>
    public byte StringKey { get; private set; } = (byte)Random.Shared.Next(1, 256);

    /// <summary>反调试注入。</summary>
    public bool AntiDebug { get; private set; }

    /// <summary>完整性校验（产物尾部 SHA-256 + 入口校验调用）。</summary>
    public bool Integrity { get; private set; }

    /// <summary>控制流平坦化。</summary>
    public bool ControlFlow { get; private set; }

    /// <summary>引用代理（把直接调用换成经 __Proxy 间接调用）。</summary>
    public bool Proxy { get; private set; }

    /// <summary>反调试/完整性校验要挂的入口方法名，默认 WPF 的 <c>App.OnStartup</c>。</summary>
    public string HookMethod { get; private set; } = "OnStartup";

    /// <summary>反调试命中后的处置动作，默认只置位降级。</summary>
    public DetectionAction AntiDebugAction { get; private set; } = DetectionAction.Degrade;

    /// <summary>完整性校验命中后的处置动作，默认只置位降级。</summary>
    public DetectionAction IntegrityAction { get; private set; } = DetectionAction.Degrade;

    /// <summary>是否有任何一个 IL 变换开关打开。全关时只走重命名，产物保持原地改写的老行为。</summary>
    public bool NeedsRewrite => StringEncrypt || AntiDebug || Integrity || ControlFlow || Proxy;

    public static PcoRules Load(string path)
    {
        using var doc = JsonDocument.Parse(File.ReadAllText(path), new JsonDocumentOptions
        {
            CommentHandling = JsonCommentHandling.Skip,
            AllowTrailingCommas = true,
        });
        return Parse(doc.RootElement);
    }

    public static PcoRules Parse(JsonElement root)
    {
        var rules = new PcoRules();
        if (root.TryGetProperty("rules", out var r) && r.TryGetProperty("keep", out var keep))
        {
            foreach (var item in keep.EnumerateArray())
            {
                string? pattern = item.GetString();
                if (!string.IsNullOrWhiteSpace(pattern))
                {
                    rules.AddKeep(pattern.Trim());
                }
            }
        }

        if (root.TryGetProperty("options", out var options))
        {
            if (options.TryGetProperty("rename_namespaces", out var rn))
            {
                rules.RenameNamespaces = rn.GetBoolean();
            }
            rules.StringEncrypt = Flag(options, "string_encrypt");
            rules.AntiDebug = Flag(options, "anti_debug");
            rules.Integrity = Flag(options, "integrity");
            rules.ControlFlow = Flag(options, "control_flow");
            rules.Proxy = Flag(options, "proxy");
            rules.AntiDebugAction = ParseAction(options, "anti_debug_action");
            rules.IntegrityAction = ParseAction(options, "integrity_action");
            if (options.TryGetProperty("string_key", out var sk))
            {
                rules.StringKey = unchecked((byte)sk.GetInt32());
            }
            if (options.TryGetProperty("hook_method", out var hm) && hm.GetString() is { Length: > 0 } hook)
            {
                rules.HookMethod = hook;
            }
        }
        return rules;
    }

    /// <summary>读一个布尔开关。缺省与写成 false 都按关处理，只有显式 true 才开。</summary>
    private static bool Flag(JsonElement options, string name) =>
        options.TryGetProperty(name, out JsonElement v) && v.ValueKind == JsonValueKind.True;

    /// <summary>读一个处置动作。缺省或非字符串按 Degrade 处理，写了非法值直接报错，避免拼错后静默降级。</summary>
    private static DetectionAction ParseAction(JsonElement options, string name)
    {
        if (!options.TryGetProperty(name, out JsonElement v) || v.ValueKind != JsonValueKind.String)
        {
            return DetectionAction.Degrade;
        }
        string value = (v.GetString() ?? "").Trim().ToLowerInvariant();
        return value switch
        {
            "degrade" => DetectionAction.Degrade,
            "exit" => DetectionAction.Exit,
            "report" => DetectionAction.Report,
            _ => throw new ArgumentException($"options.{name} 取值无效：{value}（可选 degrade|exit|report）"),
        };
    }

    private void AddKeep(string pattern)
    {
        int sep = pattern.IndexOf("::", StringComparison.Ordinal);
        if (sep < 0)
        {
            // 保类型即保它的全部成员：BAML 的事件处理器、x:Name 字段都挂在类型上，
            // 只保类型不保成员等于没保。
            _keepTypes.Add(Glob(pattern));
            return;
        }
        _keepMembers.Add((Glob(pattern[..sep]), Glob(pattern[(sep + 2)..])));
    }

    /// <summary>该类型（连同其全部成员）是否保留。</summary>
    public bool KeepsType(string fullName) => _keepTypes.Any(re => re.IsMatch(fullName));

    /// <summary>该成员是否被显式保留。</summary>
    public bool KeepsMember(string typeFullName, string memberName) =>
        _keepMembers.Any(p => p.Type.IsMatch(typeFullName) && p.Member.IsMatch(memberName));

    /// <summary>把只含 <c>*</c> 的 glob 转成整串锚定的正则。</summary>
    private static Regex Glob(string pattern)
    {
        var sb = new StringBuilder("^");
        foreach (char c in pattern)
        {
            sb.Append(c == '*' ? ".*" : Regex.Escape(c.ToString()));
        }
        sb.Append('$');
        return new Regex(sb.ToString(), RegexOptions.CultureInvariant);
    }
}