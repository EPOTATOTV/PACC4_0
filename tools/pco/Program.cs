using PaccManager.Pco;

namespace PaccManager.Pco;

/// <summary>
/// PCO 命令行入口。
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
            renamer.Run(options.Input, options.Output, options.Mapping);
            Console.WriteLine($"PCO: 改写类名 {renamer.RenamedTypes} 个、成员名 {renamer.RenamedMembers} 个 -> {options.Output}");
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