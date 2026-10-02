using PaccManager.Pco;

namespace PaccManager.Pco;

/// <summary>
/// PCO 命令行入口。
///
/// <para>流水线固定为「先重命名，再 IL 变换」：重命名是原地改 #Strings 堆，不动任何偏移；
/// IL 变换必须重建整套元数据，只能在改名之后进行，否则重建会把刚改好的名字再搬一次。</para>
///
/// <para>开了 IL 变换就整个重建一遍元数据；一个都没开时保持老行为——原地改写后原样落盘。</para>
///
/// <code>pco -i PaccManager.dll -o PaccManager.obf.dll --rules pco-rules.json --mapping pco-mapping.txt</code>
/// </summary>
internal static class Program
{
    private static int Main(string[] args)
    {
        try
        {
            var options = Options.Parse(args);
            PcoRules rules = PcoRules.Load(options.Rules);

            var renamer = new AssemblyRenamer(rules);
            byte[] bytes = renamer.RunBytes(File.ReadAllBytes(options.Input), options.Mapping);

            var stats = new List<string>
            {
                $"类名 {renamer.RenamedTypes} 个",
                $"成员名 {renamer.RenamedMembers} 个",
            };

            AssemblyRewriter? rewriter = null;
            if (rules.NeedsRewrite)
            {
                rewriter = new AssemblyRewriter(bytes);
                if (rules.StringEncrypt)
                {
                    rewriter.EnableStringEncryption(rules.StringKey);
                }
                if (rules.AntiDebug)
                {
                    rewriter.EnableAntiDebug(rules.HookMethod);
                }
                if (rules.Integrity)
                {
                    rewriter.EnableIntegrity(rules.HookMethod);
                }
                // 代理排在平坦化之前：先换掉调用点，平坦化再统一把这些调用摊进状态机。
                if (rules.Proxy)
                {
                    rewriter.EnableProxy();
                }
                if (rules.ControlFlow)
                {
                    rewriter.EnableControlFlow();
                }
                bytes = rewriter.Run();
            }

            File.WriteAllBytes(options.Output, bytes);
            AssemblyRenamer.Verify(options.Output);

            if (rewriter is not null)
            {
                if (rewriter.EncryptedStrings > 0)
                {
                    stats.Add($"加密字符串 {rewriter.EncryptedStrings} 个");
                }
                if (rewriter.AntiDebugInjected)
                {
                    stats.Add("反调试");
                }
                if (rewriter.IntegrityInjected)
                {
                    stats.Add("完整性校验");
                }
                if (rewriter.FlattenedMethods > 0)
                {
                    stats.Add($"平坦化方法 {rewriter.FlattenedMethods} 个");
                }
                if (rewriter.ProxiedCalls > 0)
                {
                    stats.Add($"代理调用 {rewriter.ProxiedCalls} 处");
                }
            }
            Console.WriteLine($"PCO: {string.Join("、", stats)} -> {options.Output}");
            return 0;
        }
        catch (Exception e)
        {
            Console.Error.WriteLine($"PCO 失败：{e.Message}");
            return 1;
        }
    }

    private sealed record Options(string Input, string Output, string Rules, string? Mapping)
    {
        public static Options Parse(string[] args)
        {
            string? input = null;
            string? output = null;
            string? rules = null;
            string? mapping = null;
            for (int i = 0; i < args.Length; i++)
            {
                switch (args[i])
                {
                    case "-i" or "--in":
                        input = Next(args, ref i);
                        break;
                    case "-o" or "--out":
                        output = Next(args, ref i);
                        break;
                    case "--rules":
                        rules = Next(args, ref i);
                        break;
                    case "--mapping":
                        mapping = Next(args, ref i);
                        break;
                    default:
                        throw new ArgumentException($"无法识别的参数：{args[i]}");
                }
            }
            if (input is null || output is null || rules is null)
            {
                throw new ArgumentException("用法：pco -i <in.dll> -o <out.dll> --rules <rules.json> [--mapping <map.txt>]");
            }
            return new Options(input, output, rules, mapping);
        }

        private static string Next(string[] args, ref int i) =>
            ++i < args.Length ? args[i] : throw new ArgumentException($"参数 {args[i - 1]} 缺少取值");
    }
}