using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection.Metadata;
using System.Reflection.PortableExecutable;
using System.Runtime.CompilerServices;
using System.Text;
using PaccManager.Pco;
using Pco.Tests.Fixture;

namespace PaccManager.Pco.Tests;

/// <summary>
/// PCO 自测。刻意不引 xunit/nunit —— 与被测工具一样零第三方依赖，跑法：
/// <c>dotnet run --project tools/pco/tests</c>。
///
/// <para>被测输入就是本程序集自己：它是 Roslyn 正常产出的一份真实程序集，有命名空间、嵌套类、
/// 虚方法覆写、枚举、P/Invoke、泛型、字符串字面量，比手工构造的元数据更能暴露问题。</para>
/// </summary>
internal static class Tests
{
    private static int _passed;
    private static readonly List<string> Failures = [];

    private static int Main()
    {
        RulesSuite();
        RoundTripSuite();

        Console.WriteLine($"PCO 自测：{_passed} 项通过，{Failures.Count} 项失败");
        foreach (string f in Failures)
        {
            Console.WriteLine("  FAIL " + f);
        }
        return Failures.Count == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------
    // 规则解析
    // ------------------------------------------------------------------

    private static void RulesSuite()
    {
        string json = """
        {
          // 注释与尾逗号都要能解析（pco-rules.json 就是这么写的）
          "rules": { "keep": ["PaccManager.App", "PaccManager.Views.*", "*::.ctor"], },
          "options": { "rename_namespaces": false, },
        }
        """;
        string tmp = Path.Combine(Path.GetTempPath(), "pco-rules-" + Guid.NewGuid().ToString("N") + ".json");
        File.WriteAllText(tmp, json, new UTF8Encoding(false));
        try
        {
            PcoRules rules = PcoRules.Load(tmp);
            Check(rules.KeepsType("PaccManager.App"), "keep 命中完整类型名");
            Check(rules.KeepsType("PaccManager.Views.Home"), "keep 支持 * 通配");
            Check(!rules.KeepsType("PaccManager.MainWindow"), "未列入的类型不保留");
            Check(rules.KeepsMember("Any.Type", ".ctor"), "keep 支持 类型::成员 写法");
            Check(!rules.KeepsMember("Any.Type", "Run"), "未列入的成员不保留");
            Check(!rules.RenameNamespaces, "options.rename_namespaces=false 生效");
        }
        finally
        {
            File.Delete(tmp);
        }

        // 真正随发行走的那份规则也要能解析，且必须保住承载 BAML 的两个类型。
        PcoRules shipped = PcoRules.Load(Path.Combine(RepoRoot(), "tools", "windows-gui", "pco-rules.json"));
        Check(shipped.KeepsType("PaccManager.App"), "发行规则保留 PaccManager.App");
        Check(shipped.KeepsType("PaccManager.MainWindow"), "发行规则保留 PaccManager.MainWindow");
        Check(!shipped.KeepsType("PaccManager.Services.ConfigCrypt"), "发行规则不保留服务类（否则混淆白做）");
    }

    // ------------------------------------------------------------------
    // 真实程序集往返
    // ------------------------------------------------------------------

    private static void RoundTripSuite()
    {
        string input = typeof(Kept).Assembly.Location;
        string dir = Path.Combine(Path.GetTempPath(), "pco-selftest-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string output = Path.Combine(dir, "Pco.Tests.obf.dll");
        string mapping = Path.Combine(dir, "mapping.txt");

        try
        {
            string rulesPath = Path.Combine(dir, "rules.json");
            File.WriteAllText(rulesPath,
                """{ "rules": { "keep": ["Pco.Tests.Fixture.Kept"] }, "options": { "rename_namespaces": true } }""",
                new UTF8Encoding(false));

            var renamer = new AssemblyRenamer(PcoRules.Load(rulesPath));
            renamer.Run(input, output, mapping);

            Check(File.Exists(output), "产出混淆后的程序集");
            Check(renamer.RenamedTypes > 0, "至少改掉了一个类型名");
            Check(renamer.RenamedMembers > 0, "至少改掉了一个成员名");
            Check(renamer.Mapping.Contains("Pco.Tests.Fixture.Renamed -> ", StringComparison.Ordinal),
                "映射表记下了类型改名");
            Check(!renamer.Mapping.Contains("Pco.Tests.Fixture.Kept", StringComparison.Ordinal),
                "映射表里没有 keep 类型（keep 生效）");

            byte[] before = File.ReadAllBytes(input);
            byte[] after = File.ReadAllBytes(output);
            Equal(after.Length, before.Length, "产物大小与输入一致（原地改写不动偏移）");

            int diff = 0;
            int outsideHeap = -1;
            using (var pe = new PEReader(new MemoryStream(before)))
            {
                StringHeapMap heap = StringHeapMap.Locate(before, pe.PEHeaders);
                for (int i = 0; i < before.Length; i++)
                {
                    if (before[i] == after[i])
                    {
                        continue;
                    }
                    diff++;
                    if (!heap.ContainsFileOffset(i))
                    {
                        outsideHeap = i;
                        break;
                    }
                }
            }
            Check(diff > 0, "确实改写了字节");
            Check(outsideHeap < 0, $"所有差异字节都落在 #Strings 堆内（首个越界偏移 {outsideHeap}）");

            Inventory beforeNames = InventoryOf(input);
            Inventory afterNames = InventoryOf(output);
            Equal(afterNames.Types.Count, beforeNames.Types.Count, "类型数量不变");

            Check(beforeNames.Types.Contains("Pco.Tests.Fixture.Kept"), "输入里有 keep 类型");
            Check(afterNames.Types.Contains("Pco.Tests.Fixture.Kept"), "keep 类型保名");
            Check(!afterNames.Types.Contains("Pco.Tests.Fixture.Renamed"), "可改类型已改名（残留在 #Strings 里说明被后缀共享）");
            Check(!afterNames.Types.Contains("Pco.Tests.Fixture.RenamedChild"), "派生类已改名");
            Check(!afterNames.Types.Contains("Pco.Tests.Fixture.Renamed+Nested"), "嵌套类跟着外层改名");
            Check(!afterNames.Types.Contains("Pco.Tests.Fixture.Mode"), "枚举类型已改名");
            Check(!afterNames.Types.Contains("Pco.Tests.Fixture.NativeProbe"), "静态类已改名");

            // 保名项：BAML/序列化/虚表/P-Invoke 全靠名字对上，少一个都会在运行期炸。
            Check(afterNames.Properties.Contains("KeptProperty"), "属性名保名");
            Check(afterNames.Events.Contains("KeptEvent"), "事件名保名");
            Check(afterNames.Methods.Contains("Describe"), "keep 类型的方法保名");
            Check(afterNames.Methods.Contains(".ctor"), "构造函数名保名");
            Check(afterNames.Methods.Contains("Score"), "虚方法保名（覆写按名字绑定）");
            Check(afterNames.Fields.IsSupersetOf(["Off", "Warn", "Enforce"]), "枚举成员名保名");
            Check(afterNames.Imports.SetEquals(beforeNames.Imports), "P/Invoke 入口名保名");
        }
        finally
        {
            try
            {
                Directory.Delete(dir, true);
            }
            catch (IOException)
            {
                // 清理失败不影响结论
            }
        }
    }

    // ------------------------------------------------------------------
    // 元数据清点
    // ------------------------------------------------------------------

    private sealed record Inventory(
        HashSet<string> Types,
        HashSet<string> Methods,
        HashSet<string> Fields,
        HashSet<string> Properties,
        HashSet<string> Events,
        HashSet<string> Imports);

    private static Inventory InventoryOf(string path)
    {
        using var pe = new PEReader(File.OpenRead(path));
        MetadataReader md = pe.GetMetadataReader();
        var memo = new Dictionary<TypeDefinitionHandle, string>();

        var types = new HashSet<string>(StringComparer.Ordinal);
        var methods = new HashSet<string>(StringComparer.Ordinal);
        var fields = new HashSet<string>(StringComparer.Ordinal);
        var properties = new HashSet<string>(StringComparer.Ordinal);
        var events = new HashSet<string>(StringComparer.Ordinal);
        var imports = new HashSet<string>(StringComparer.Ordinal);

        foreach (TypeDefinitionHandle h in md.TypeDefinitions)
        {
            types.Add(FullNameOf(md, h, memo));
            TypeDefinition td = md.GetTypeDefinition(h);
            foreach (FieldDefinitionHandle fh in td.GetFields())
            {
                fields.Add(md.GetString(md.GetFieldDefinition(fh).Name));
            }
            foreach (PropertyDefinitionHandle ph in td.GetProperties())
            {
                properties.Add(md.GetString(md.GetPropertyDefinition(ph).Name));
            }
            foreach (EventDefinitionHandle eh in td.GetEvents())
            {
                events.Add(md.GetString(md.GetEventDefinition(eh).Name));
            }
        }
        foreach (MethodDefinitionHandle h in md.MethodDefinitions)
        {
            MethodDefinition m = md.GetMethodDefinition(h);
            methods.Add(md.GetString(m.Name));
            if ((m.Attributes & System.Reflection.MethodAttributes.PinvokeImpl) != 0)
            {
                imports.Add(md.GetString(m.GetImport().Name));
            }
        }
        return new Inventory(types, methods, fields, properties, events, imports);
    }

    private static string FullNameOf(MetadataReader md, TypeDefinitionHandle h,
        Dictionary<TypeDefinitionHandle, string> memo)
    {
        if (memo.TryGetValue(h, out string? cached))
        {
            return cached;
        }
        TypeDefinition td = md.GetTypeDefinition(h);
        string simple = md.GetString(td.Name);
        string ns = md.GetString(td.Namespace);
        string result = td.IsNested
            ? FullNameOf(md, td.GetDeclaringType(), memo) + "+" + simple
            : ns.Length == 0 ? simple : ns + "." + simple;
        memo[h] = result;
        return result;
    }

    /// <summary>仓库根目录。测试的当前目录随运行方式变化，按源码路径反推才稳。</summary>
    private static string RepoRoot([CallerFilePath] string testFile = "") =>
        Path.GetFullPath(Path.Combine(Path.GetDirectoryName(testFile)!, "..", "..", ".."));

    private static void Check(bool ok, string what)
    {
        if (ok)
        {
            _passed++;
        }
        else
        {
            Failures.Add(what);
        }
    }

    private static void Equal(object? actual, object? expected, string what) =>
        Check(Equals(actual, expected), $"{what}（实际 {actual}，期望 {expected}）");
}