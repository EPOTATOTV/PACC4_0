using System.Text;
using System.Text.RegularExpressions;

namespace PaccManager.Pco;

/// <summary>
/// PCO 崩溃堆栈还原：读 <c>pco-mapping.txt</c>，把混淆后的类型名 / 成员名换回原名。
///
/// <para>mapping 由 <see cref="AssemblyRenamer"/> 输出，方向是「原名 -&gt; 混淆名」，本工具内部
/// 反向成「混淆名 -&gt; 原名」。两条行的形态：</para>
/// <pre>
/// PaccManager.Services.ProbeLauncher -> a.b
/// PaccManager.Services.ProbeLauncher::Start -> a.b::c
/// </pre>
/// <para>成员行用 <c>::</c> 分隔类型与成员，所以类型名、成员名各自能拆干净。还原时类型名按
/// 词边界做全文替换（覆盖 <c>at</c> 帧与 <c>Caused by</c> / 内层异常头里的异常类型）；成员名
/// 只在 <c>at &lt;类型&gt;.&lt;成员&gt;(...)</c> 形态的帧行里替换，否则正文里的裸短名会被误伤。</para>
///
/// <para>用法（与主命令共用同一个可执行文件）：</para>
/// <pre>
/// pco --retrace &lt;mapping&gt; [堆栈文件]
/// </pre>
/// <para>省略堆栈文件时从标准输入读，还原结果写到标准输出。PCO 不保留 PDB 行号信息，
/// 帧里的 <c>in file:line</c> 后缀原样保留。</para>
/// </summary>
internal static class PcoRetrace
{
    /// <summary>at 帧：<c>at &lt;类型&gt;.&lt;成员&gt;(&lt;参数或程序集&gt;)</c>，后可能跟 PDB 后缀。</summary>
    private static readonly Regex Frame = new(@"^(\s*at\s+)([^()]+)\((.*)$", RegexOptions.Compiled);

    /// <summary>入口：<c>pco --retrace &lt;mapping&gt; [stacktrace-file]</c>。</summary>
    public static int Run(string[] args)
    {
        if (args.Length is < 1 or > 2)
        {
            Console.Error.WriteLine("用法: pco --retrace <mapping> [stacktrace-file]");
            return 2;
        }
        Mapping mapping = Mapping.Load(args[0]);
        string stacktrace = args.Length == 2
            ? File.ReadAllText(args[1])
            : Console.In.ReadToEnd();
        Console.WriteLine(mapping.Retrace(stacktrace));
        return 0;
    }

    /// <summary>加载 mapping 文件；供命令行入口与自测使用。</summary>
    internal static Mapping LoadMapping(string path) => Mapping.Load(path);

    /// <summary>混淆名 -&gt; 原名 的反查表。</summary>
    internal sealed class Mapping
    {
        /// <summary>类型反查表，长名在前：点号分隔的类名里短名可能是长名的前缀。</summary>
        private readonly List<KeyValuePair<string, string>> _types;

        /// <summary>成员反查表，键是 <c>混淆类型 + "\\0" + 混淆成员</c>。因为成员短名按类型各自分配。</summary>
        private readonly Dictionary<string, string> _members;

        private Mapping(List<KeyValuePair<string, string>> types, Dictionary<string, string> members)
        {
            _types = types;
            _members = members;
        }

        public static Mapping Load(string path)
        {
            var types = new Dictionary<string, string>(StringComparer.Ordinal);
            var members = new Dictionary<string, string>(StringComparer.Ordinal);

            foreach (string raw in File.ReadAllLines(path))
            {
                int arrow = raw.LastIndexOf(" -> ", StringComparison.Ordinal);
                if (arrow < 0)
                {
                    continue;
                }
                string left = raw[..arrow].Trim();
                string right = raw[(arrow + 4)..].Trim();
                if (left.Length == 0 || right.Length == 0)
                {
                    continue;
                }

                int memberSep = left.IndexOf("::", StringComparison.Ordinal);
                if (memberSep >= 0)
                {
                    int rightSep = right.IndexOf("::", StringComparison.Ordinal);
                    if (rightSep < 0)
                    {
                        continue;
                    }
                    string obfType = right[..rightSep];
                    string obfMember = right[(rightSep + 2)..];
                    string origMember = left[(memberSep + 2)..];
                    members[obfType + "\0" + obfMember] = origMember;
                }
                else
                {
                    types[right] = left;
                }
            }

            List<KeyValuePair<string, string>> ordered = types
                .OrderByDescending(t => t.Key.Length)
                .ToList();
            return new Mapping(ordered, members);
        }

        /// <summary>还原一段堆栈文本，保留原有行结构。</summary>
        public string Retrace(string stacktrace)
        {
            if (string.IsNullOrEmpty(stacktrace))
            {
                return stacktrace;
            }
            string[] lines = stacktrace.Split('\n');
            var outText = new StringBuilder(stacktrace.Length + 64);
            for (int i = 0; i < lines.Length; i++)
            {
                if (i > 0)
                {
                    outText.Append('\n');
                }
                outText.Append(RetraceLine(lines[i]));
            }
            return outText.ToString();
        }

        private string RetraceLine(string line)
        {
            Match frame = Frame.Match(line);
            if (frame.Success)
            {
                string qualified = frame.Groups[2].Value;
                foreach (KeyValuePair<string, string> type in _types)
                {
                    // 必须整段是「类型 + '.' + 成员」，避免把前缀相同的另一个类型拆错。
                    if (qualified.Length <= type.Key.Length
                        || !qualified.StartsWith(type.Key, StringComparison.Ordinal)
                        || qualified[type.Key.Length] != '.')
                    {
                        continue;
                    }
                    string member = qualified[(type.Key.Length + 1)..];
                    string origMember = _members.TryGetValue(type.Key + "\0" + member, out string? mapped)
                        ? mapped
                        : member;
                    line = frame.Groups[1].Value + type.Value + "." + origMember + "(" + frame.Groups[3].Value;
                    break;
                }
            }
            return ReplaceTypeNames(line);
        }

        /// <summary>按词边界全文替换类型名，覆盖异常头与帧里剩余的类名；长名优先。</summary>
        private string ReplaceTypeNames(string line)
        {
            foreach (KeyValuePair<string, string> type in _types)
            {
                if (line.Contains(type.Key, StringComparison.Ordinal))
                {
                    line = Regex.Replace(
                        line,
                        "(?<![\\w.])" + Regex.Escape(type.Key) + "(?![\\w])",
                        _ => type.Value);
                }
            }
            return line;
        }
    }
}
