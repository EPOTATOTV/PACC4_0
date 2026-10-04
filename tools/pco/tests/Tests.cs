using System;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using System.Reflection.Metadata;
using System.Reflection.Metadata.Ecma335;
using System.Reflection.PortableExecutable;
using System.Runtime.CompilerServices;
using System.Runtime.Loader;
using System.Security.Cryptography;
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
        RewriteRoundTripSuite();
        StringEncryptionSuite();
        AntiDebugSuite();
        IntegritySuite();
        ControlFlowSuite();
        ProxySuite();
        PipelineSuite();
        RetraceSuite();
        DetectionActionSuite();

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
          "options": {
            "rename_namespaces": false,
            "string_encrypt": true,
            "string_key": 90,
            "anti_debug": true,
            "integrity": false,
            "control_flow": true,
            "proxy": true,
            "hook_method": "OnActivated",
            "anti_debug_action": "exit",
            "integrity_action": "report",
          },
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

            Check(rules.StringEncrypt, "options.string_encrypt=true 生效");
            Equal((int)rules.StringKey, 90, "options.string_key 生效");
            Check(rules.AntiDebug, "options.anti_debug=true 生效");
            Check(!rules.Integrity, "options.integrity 缺省/false 时关掉");
            Check(rules.ControlFlow, "options.control_flow=true 生效");
            Check(rules.Proxy, "options.proxy=true 生效");
            Equal(rules.HookMethod, "OnActivated", "options.hook_method 生效");
            Equal(rules.AntiDebugAction, DetectionAction.Exit, "options.anti_debug_action=exit 生效");
            Equal(rules.IntegrityAction, DetectionAction.Report, "options.integrity_action=report 生效");
            Check(rules.NeedsRewrite, "只要有一个 IL 变换开着就要重建元数据");
        }
        finally
        {
            File.Delete(tmp);
        }

        // 一个开关都不开时必须退回「只重命名」的老行为，否则历史构建会突然多出一堆变换。
        string offPath = Path.Combine(Path.GetTempPath(), "pco-rules-off-" + Guid.NewGuid().ToString("N") + ".json");
        File.WriteAllText(offPath, """{ "rules": { "keep": [] }, "options": { "rename_namespaces": true } }""",
            new UTF8Encoding(false));
        try
        {
            PcoRules off = PcoRules.Load(offPath);
            Check(!off.NeedsRewrite, "不写变换开关时不重建元数据");
            Equal(off.HookMethod, "OnStartup", "hook_method 缺省是 WPF 的 OnStartup");
            Check(off.StringKey != 0, "缺省字符串密钥随机取非零字节");
            Check(off.AntiDebugAction == DetectionAction.Degrade, "处置动作缺省为 degrade（不自毁）");
            Check(off.IntegrityAction == DetectionAction.Degrade, "完整性处置动作缺省为 degrade");
        }
        finally
        {
            File.Delete(offPath);
        }

        // 动作拼错要立刻报错，不能静默当成 degrade——否则配置写错却不自知。
        string badPath = Path.Combine(Path.GetTempPath(), "pco-rules-bad-" + Guid.NewGuid().ToString("N") + ".json");
        File.WriteAllText(badPath,
            """{ "rules": { "keep": [] }, "options": { "anti_debug": true, "anti_debug_action": "kill" } }""",
            new UTF8Encoding(false));
        try
        {
            bool threw = false;
            try
            {
                PcoRules.Load(badPath);
            }
            catch (ArgumentException)
            {
                threw = true;
            }
            Check(threw, "非法的 anti_debug_action 直接报错");
        }
        finally
        {
            File.Delete(badPath);
        }

        // 真正随发行走的那份规则也要能解析，且必须保住承载 BAML 的两个类型。
        PcoRules shipped = PcoRules.Load(Path.Combine(RepoRoot(), "tools", "windows-gui", "pco-rules.json"));
        Check(shipped.KeepsType("PaccManager.App"), "发行规则保留 PaccManager.App");
        Check(shipped.KeepsType("PaccManager.MainWindow"), "发行规则保留 PaccManager.MainWindow");
        Check(!shipped.KeepsType("PaccManager.Services.ConfigCrypt"), "发行规则不保留服务类（否则混淆白做）");
        Check(shipped.StringEncrypt && shipped.AntiDebug && shipped.Integrity
            && shipped.ControlFlow && shipped.Proxy, "发行规则开满六个变换");
        Equal(shipped.HookMethod, "OnStartup", "发行规则的注入点是 App.OnStartup");
        Check(shipped.RenameNamespaces, "发行规则拍平命名空间");
        Check(shipped.AntiDebugAction == DetectionAction.Degrade && shipped.IntegrityAction == DetectionAction.Degrade,
            "发行规则的处置动作是缺省的 degrade（命中只降级不自毁）");
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
    // 元数据重建往返（注入类变换的地基）
    // ------------------------------------------------------------------

    /// <summary>
    /// 用 <see cref="AssemblyRewriter"/> 把自身程序集整份重建一遍，再真正加载执行。
    /// 这里不做任何变换，要求产物与输入在结构上、行为上完全等价——只有这个地基稳了，
    /// 后面往上叠字符串加密/反调试/控制流平坦化才有意义。
    /// </summary>
    private static void RewriteRoundTripSuite()
    {
        string input = typeof(Kept).Assembly.Location;
        string srcDir = Path.GetDirectoryName(input)!;
        string dir = Path.Combine(Path.GetTempPath(), "pco-rt-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string output = Path.Combine(dir, "Pco.Tests.rt.dll");
        var alc = new AssemblyLoadContext("pco-rt-" + Guid.NewGuid().ToString("N"), isCollectible: true);

        try
        {
            var rewriter = new AssemblyRewriter(File.ReadAllBytes(input));
            byte[] rewritten = rewriter.Run();
            Check(rewritten.Length > 0, "往返重写产出非空程序集");
            File.WriteAllBytes(output, rewritten);

            // 结构不变：没有增减任何类型、成员、导入。
            Inventory before = InventoryOf(input);
            Inventory after = InventoryOf(output);
            Check(after.Types.SetEquals(before.Types), "往返后类型名集合一致");
            Check(after.Methods.SetEquals(before.Methods), "往返后方法名集合一致");
            Check(after.Fields.SetEquals(before.Fields), "往返后字段名集合一致");
            Check(after.Properties.SetEquals(before.Properties), "往返后属性名集合一致");
            Check(after.Events.SetEquals(before.Events), "往返后事件名集合一致");
            Check(after.Imports.SetEquals(before.Imports), "往返后 P/Invoke 入口集合一致");

            // 真正加载并执行：这是唯一能证明 IL 偏移、#US 令牌、异常区、P/Invoke 都搬对了的办法。
            alc.Resolving += (ctx, name) =>
            {
                string candidate = Path.Combine(srcDir, name.Name + ".dll");
                return File.Exists(candidate) ? ctx.LoadFromAssemblyPath(candidate) : null;
            };
            Assembly asm = alc.LoadFromAssemblyPath(output);
            Check(asm.GetName().Name == "Pco.Tests", "往返产物程序集标识不变");

            object keptInstance = Activator.CreateInstance(Require(asm, "Pco.Tests.Fixture.Kept"))!;
            Equal(Require(asm, "Pco.Tests.Fixture.Kept").GetMethod("Describe")!.Invoke(keptInstance, null),
                "kept1", "keep 类型方法可执行（字段初始化 + 事件触发都正常）");
            Equal(Require(asm, "Pco.Tests.Fixture.Kept").GetProperty("KeptProperty")!.GetValue(keptInstance),
                "kept", "属性默认值不变");

            // Bump 里既有字符串字面量（#US 重映射），又有对私有静态方法的调用。
            object renamedInstance = Activator.CreateInstance(Require(asm, "Pco.Tests.Fixture.Renamed"))!;
            Equal(Require(asm, "Pco.Tests.Fixture.Renamed").GetMethod("Bump")!.Invoke(renamedInstance, null),
                "https://api.potatotv.asia/v1/report1", "字符串字面量与私有方法调用都正确");

            object childInstance = Activator.CreateInstance(Require(asm, "Pco.Tests.Fixture.RenamedChild"))!;
            Equal(Require(asm, "Pco.Tests.Fixture.RenamedChild").GetMethod("Score")!.Invoke(childInstance, null),
                1, "虚方法覆写仍按虚表分派");

            Type probe = Require(asm, "Pco.Tests.Fixture.NativeProbe");
            uint pid = (uint)probe.GetMethod("CurrentProcessId", BindingFlags.NonPublic | BindingFlags.Static)!
                .Invoke(null, null)!;
            Check(pid > 0, "P/Invoke 仍可调用（Import 表与 EntryPoint 搬对了）");

            Check(rewriter.RewrittenMethods == 0, "无变换回调时不改写任何方法体");
        }
        catch (Exception ex)
        {
            Failures.Add($"往返重写抛出异常：{ex}");
        }
        finally
        {
            alc.Unload();
            try
            {
                Directory.Delete(dir, true);
            }
            catch (Exception)
            {
                // 可回收 ALC 的卸载是异步的，文件可能还没释放；临时目录留给系统回收。
            }
        }
    }

    private static Type Require(Assembly asm, string name) => asm.GetType(name, throwOnError: true)!;

    // ------------------------------------------------------------------
    // 字符串加密
    // ------------------------------------------------------------------

    /// <summary>
    /// 开启字符串加密重建自身程序集：要求明文不再出现在产物里，注入的 __Decrypt 能把密文还原，
    /// 且带 try/catch/finally 的方法在方法体长度变化后依然正确。
    /// </summary>
    private static void StringEncryptionSuite()
    {
        string input = typeof(Kept).Assembly.Location;
        string srcDir = Path.GetDirectoryName(input)!;
        string dir = Path.Combine(Path.GetTempPath(), "pco-enc-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string output = Path.Combine(dir, "Pco.Tests.enc.dll");
        var alc = new AssemblyLoadContext("pco-enc-" + Guid.NewGuid().ToString("N"), isCollectible: true);

        try
        {
            var rewriter = new AssemblyRewriter(File.ReadAllBytes(input));
            rewriter.EnableStringEncryption(0x5A);
            byte[] rewritten = rewriter.Run();
            File.WriteAllBytes(output, rewritten);

            Check(rewriter.EncryptedStrings > 0, "至少加密了一个字符串字面量");

            // 明文以 UTF-16 形式存在 #US 堆里，加密后整段字节序列应当消失。
            Check(!ContainsUtf16(rewritten, "https://api.potatotv.asia/v1/report"),
                "报告地址明文不再出现在产物中");
            Check(!ContainsUtf16(rewritten, "guard-fail-token"), "异常文案明文不再出现");

            using (var pe = new PEReader(File.OpenRead(output)))
            {
                MetadataReader md = pe.GetMetadataReader();
                bool found = false;
                foreach (TypeDefinitionHandle h in md.TypeDefinitions)
                {
                    TypeDefinition td = md.GetTypeDefinition(h);
                    if (md.GetString(td.Namespace) == "PaccManager" && md.GetString(td.Name) == "__Decrypt")
                    {
                        found = true;
                    }
                }
                Check(found, "注入了 __Decrypt 类型");
            }

            alc.Resolving += (ctx, name) =>
            {
                string candidate = Path.Combine(srcDir, name.Name + ".dll");
                return File.Exists(candidate) ? ctx.LoadFromAssemblyPath(candidate) : null;
            };
            Assembly asm = alc.LoadFromAssemblyPath(output);

            object renamedInstance = Activator.CreateInstance(Require(asm, "Pco.Tests.Fixture.Renamed"))!;
            Equal(Require(asm, "Pco.Tests.Fixture.Renamed").GetMethod("Bump")!.Invoke(renamedInstance, null),
                "https://api.potatotv.asia/v1/report1", "加密后的字符串被 __Decrypt 正确还原");

            Type guarded = Require(asm, "Pco.Tests.Fixture.Guarded");
            MethodInfo run = guarded.GetMethod("Run")!;
            Equal(run.Invoke(null, [false]), "guard-ok-token", "try 块内字符串还原正确");
            Equal(run.Invoke(null, [true]), "guard-caught:guard-fail-token",
                "异常表的 try/catch/finally 边界在长度变化后仍正确");
            Equal(guarded.GetField("Done")!.GetValue(null), 2, "finally 执行了两次");
        }
        catch (Exception ex)
        {
            Failures.Add($"字符串加密抛出异常：{ex}");
        }
        finally
        {
            alc.Unload();
            try
            {
                Directory.Delete(dir, true);
            }
            catch (Exception)
            {
                // 可回收 ALC 的卸载是异步的，文件可能还没释放。
            }
        }
    }

    // ------------------------------------------------------------------
    // 反调试注入
    // ------------------------------------------------------------------

    /// <summary>
    /// 同时开字符串加密与反调试：验证注入方法/字段的行号基址会累计（__Decrypt 先占一行），
    /// P/Invoke 的 ModuleRef 与 ImplMap 补得对，且入口方法前置插指令后异常表边界正确平移。
    /// </summary>
    private static void AntiDebugSuite()
    {
        string input = typeof(Startup).Assembly.Location;
        string srcDir = Path.GetDirectoryName(input)!;
        string dir = Path.Combine(Path.GetTempPath(), "pco-ad-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string output = Path.Combine(dir, "Pco.Tests.ad.dll");
        var alc = new AssemblyLoadContext("pco-ad-" + Guid.NewGuid().ToString("N"), isCollectible: true);

        try
        {
            var rewriter = new AssemblyRewriter(File.ReadAllBytes(input));
            rewriter.EnableStringEncryption(0x33);
            rewriter.EnableAntiDebug();
            byte[] rewritten = rewriter.Run();
            File.WriteAllBytes(output, rewritten);

            Check(rewriter.AntiDebugInjected, "OnStartup 上注入了反调试调用");

            Inventory after = InventoryOf(output);
            Check(after.Methods.Contains("Start") && after.Methods.Contains("Probe"), "注入了 __AntiDebug 的方法");
            Check(after.Fields.Contains("Detected") && after.Fields.Contains("Watchdog"), "注入了 __AntiDebug 的字段");
            Check(after.Imports.IsSupersetOf(["IsDebuggerPresent", "CheckRemoteDebuggerPresent", "GetCurrentProcess"]),
                "注入了 kernel32 的三个 P/Invoke 入口");

            using (var pe = new PEReader(File.OpenRead(output)))
            {
                MetadataReader md = pe.GetMetadataReader();
                bool hasModule = false;
                for (int row = 1; row <= md.GetTableRowCount(TableIndex.ModuleRef); row++)
                {
                    if (md.GetString(md.GetModuleReference(MetadataTokens.ModuleReferenceHandle(row)).Name)
                        == "kernel32.dll")
                    {
                        hasModule = true;
                    }
                }
                Check(hasModule, "补了 kernel32.dll 的 ModuleRef");
            }

            alc.Resolving += (ctx, name) =>
            {
                string candidate = Path.Combine(srcDir, name.Name + ".dll");
                return File.Exists(candidate) ? ctx.LoadFromAssemblyPath(candidate) : null;
            };
            Assembly asm = alc.LoadFromAssemblyPath(output);

            Type startup = Require(asm, "Pco.Tests.Fixture.Startup");
            Equal(startup.GetMethod("OnStartup")!.Invoke(null, null), "startup-ok",
                "入口方法原有逻辑照常执行（前置插指令后异常边界仍正确）");
            Equal(startup.GetField("Ran")!.GetValue(null), 1, "入口方法计数加一");

            Type anti = Require(asm, "PaccManager.__AntiDebug");
            Equal(anti.GetField("Detected")!.GetValue(null), false, "未接调试器时 Detected 保持 false（只降级不自毁）");
            Check(anti.GetField("Watchdog", BindingFlags.NonPublic | BindingFlags.Static)!.GetValue(null) is not null,
                "定时复检已挂上（Timer/TimerCallback 的成员引用与委托绑定正确）");
        }
        catch (Exception ex)
        {
            Failures.Add($"反调试注入抛出异常：{ex}");
        }
        finally
        {
            alc.Unload();
            try
            {
                Directory.Delete(dir, true);
            }
            catch (Exception)
            {
                // 可回收 ALC 的卸载是异步的，文件可能还没释放。
            }
        }
    }

    // ------------------------------------------------------------------
    // 完整性校验
    // ------------------------------------------------------------------

    /// <summary>
    /// 开启完整性校验：产物尾部应等于前段的 SHA-256，注入的 <c>__Integrity::Verify</c> 在磁盘文件
    /// 未被改动时保持 <c>Detected=false</c>，尾部改掉一个字节后置 true。与字符串加密/反调试叠加，
    /// 顺带确认注入类型的行号基址会累计。
    /// </summary>
    private static void IntegritySuite()
    {
        string input = typeof(Startup).Assembly.Location;
        string srcDir = Path.GetDirectoryName(input)!;
        string dir = Path.Combine(Path.GetTempPath(), "pco-integ-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string output = Path.Combine(dir, "Pco.Tests.integ.dll");
        string badPath = Path.Combine(dir, "Pco.Tests.bad.dll");
        var alc = new AssemblyLoadContext("pco-integ-" + Guid.NewGuid().ToString("N"), isCollectible: true);
        var alcBad = new AssemblyLoadContext("pco-integ-bad-" + Guid.NewGuid().ToString("N"), isCollectible: true);

        Assembly Load(AssemblyLoadContext ctx, string path)
        {
            ctx.Resolving += (c, name) =>
            {
                string candidate = Path.Combine(srcDir, name.Name + ".dll");
                return File.Exists(candidate) ? c.LoadFromAssemblyPath(candidate) : null;
            };
            return ctx.LoadFromAssemblyPath(path);
        }

        try
        {
            var rewriter = new AssemblyRewriter(File.ReadAllBytes(input));
            rewriter.EnableStringEncryption(0x6D);
            rewriter.EnableAntiDebug();
            rewriter.EnableIntegrity();
            byte[] rewritten = rewriter.Run();
            File.WriteAllBytes(output, rewritten);

            Check(rewriter.IntegrityInjected, "OnStartup 上注入了完整性校验调用");

            int bodyLength = rewritten.Length - IntegrityCheck.TrailerSize;
            byte[] expected = SHA256.HashData(rewritten.AsSpan(0, bodyLength));
            Check(rewritten.AsSpan(bodyLength).SequenceEqual(expected), "产物尾部 32 字节等于前段 SHA-256");

            Inventory after = InventoryOf(output);
            Check(after.Methods.Contains("Verify"), "注入了 __Integrity.Verify");
            Check(after.Fields.Contains("Detected"), "注入了 __Integrity.Detected");

            Assembly asm = Load(alc, output);
            Type integ = Require(asm, "PaccManager.__Integrity");
            integ.GetMethod("Verify", BindingFlags.Public | BindingFlags.Static)!.Invoke(null, null);
            Equal(integ.GetField("Detected")!.GetValue(null), false, "文件未被改动时校验通过");

            // 入口方法里注入的调用也要能跑通：调一次 OnStartup，结果仍是 startup-ok。
            Equal(Require(asm, "Pco.Tests.Fixture.Startup").GetMethod("OnStartup")!.Invoke(null, null),
                "startup-ok", "入口方法里注入的完整性校验不影响原有逻辑");

            // 尾部在 PE 之外，改掉一个字节不影响加载，但哈希对不上，必须置位。
            byte[] tampered = (byte[])rewritten.Clone();
            tampered[^1] ^= 0xFF;
            File.WriteAllBytes(badPath, tampered);
            Assembly bad = Load(alcBad, badPath);
            Type badInteg = Require(bad, "PaccManager.__Integrity");
            badInteg.GetMethod("Verify", BindingFlags.Public | BindingFlags.Static)!.Invoke(null, null);
            Equal(badInteg.GetField("Detected")!.GetValue(null), true, "尾部被改动后检测到不一致");
        }
        catch (Exception ex)
        {
            Failures.Add($"完整性校验抛出异常：{ex}");
        }
        finally
        {
            alc.Unload();
            alcBad.Unload();
            try
            {
                Directory.Delete(dir, true);
            }
            catch (Exception)
            {
                // 可回收 ALC 的卸载是异步的，文件可能还没释放。
            }
        }
    }

    // ------------------------------------------------------------------
    // 控制流平坦化
    // ------------------------------------------------------------------

    /// <summary>
    /// 开启控制流平坦化重建自身程序集：要求产物能加载、能跑出与原始一致的结果，
    /// 可平坦化的方法被换成 switch 状态机，而栈深不为零的方法（三元表达式）原样保留。
    /// </summary>
    private static void ControlFlowSuite()
    {
        string input = typeof(Kept).Assembly.Location;
        string srcDir = Path.GetDirectoryName(input)!;
        string dir = Path.Combine(Path.GetTempPath(), "pco-cff-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string output = Path.Combine(dir, "Pco.Tests.cff.dll");
        var alc = new AssemblyLoadContext("pco-cff-" + Guid.NewGuid().ToString("N"), isCollectible: true);

        try
        {
            var rewriter = new AssemblyRewriter(File.ReadAllBytes(input));
            rewriter.EnableControlFlow();
            byte[] rewritten = rewriter.Run();
            File.WriteAllBytes(output, rewritten);

            Check(rewriter.FlattenedMethods > 0, "至少平坦化了一个方法");

            // 结构上可以看出状态机：入口是 ldc.i4 0 / stloc / br（分发器前导）。
            byte[] sumIl = MethodIl(output, "Pco.Tests.Fixture.Flattened", "Sum");
            Check(IsStateMachine(sumIl), "Sum 被改写为 switch 状态机");
            byte[] countIl = MethodIl(output, "Pco.Tests.Fixture.Flattened", "Count");
            Check(IsStateMachine(countIl), "Count 被改写为 switch 状态机");
            byte[] pickIl = MethodIl(output, "Pco.Tests.Fixture.Flattened", "Pick");
            Check(!IsStateMachine(pickIl), "三元表达式方法（汇合块入口栈非空）被跳过");

            // 真正加载执行：这是唯一能证明分支改写、状态转移、局部变量签名都正确的办法。
            alc.Resolving += (ctx, name) =>
            {
                string candidate = Path.Combine(srcDir, name.Name + ".dll");
                return File.Exists(candidate) ? ctx.LoadFromAssemblyPath(candidate) : null;
            };
            Assembly asm = alc.LoadFromAssemblyPath(output);
            Type flat = Require(asm, "Pco.Tests.Fixture.Flattened");

            MethodInfo sum = flat.GetMethod("Sum")!;
            Equal(sum.Invoke(null, [new[] { 1, 2, -3, 4 }, 100]), 7, "循环 + continue 的结果正确");
            Equal(sum.Invoke(null, [new[] { 1, 2, 5, 4 }, 3]), 3, "循环 + break 的结果正确");
            Equal(sum.Invoke(null, [Array.Empty<int>(), 10]), 0, "空数组直接返回 0");

            MethodInfo count = flat.GetMethod("Count")!;
            Equal(count.Invoke(null, [10]), 5, "while 循环结果正确");
            Equal(count.Invoke(null, [0]), 0, "循环不执行时结果正确");

            MethodInfo pick = flat.GetMethod("Pick")!;
            Equal(pick.Invoke(null, [3]), "positive", "未平坦化的方法行为不变");
            Equal(pick.Invoke(null, [-3]), "non-positive", "未平坦化的方法行为不变");
        }
        catch (Exception ex)
        {
            Failures.Add($"控制流平坦化抛出异常：{ex}");
        }
        finally
        {
            alc.Unload();
            try
            {
                Directory.Delete(dir, true);
            }
            catch (Exception)
            {
                // 可回收 ALC 的卸载是异步的，文件可能还没释放。
            }
        }
    }

    /// <summary>
    /// 引用代理：直接调用被换成 __Proxy 转发方法后，产物仍能加载执行——
    /// 委托调用、静态调用、虚方法分派、base 调用、try/catch 全都要保持一致。
    /// </summary>
    private static void ProxySuite()
    {
        string input = typeof(Kept).Assembly.Location;
        string srcDir = Path.GetDirectoryName(input)!;
        string dir = Path.Combine(Path.GetTempPath(), "pco-proxy-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string output = Path.Combine(dir, "Pco.Tests.proxy.dll");
        var alc = new AssemblyLoadContext("pco-proxy-" + Guid.NewGuid().ToString("N"), isCollectible: true);

        try
        {
            var rewriter = new AssemblyRewriter(File.ReadAllBytes(input));
            rewriter.EnableProxy();
            byte[] rewritten = rewriter.Run();
            File.WriteAllBytes(output, rewritten);

            Check(rewriter.ProxiedCalls > 0, "至少改写了一个直接调用");
            Check(InventoryOf(output).Types.Contains("PaccManager.__Proxy"), "注入了 __Proxy 类型");

            Assembly asm = LoadInto(alc, output, srcDir);

            object kept = Activator.CreateInstance(Require(asm, "Pco.Tests.Fixture.Kept"))!;
            Equal(Require(asm, "Pco.Tests.Fixture.Kept").GetMethod("Describe")!.Invoke(kept, null),
                "kept1", "委托调用与静态 Concat 经代理后结果不变");

            object renamed = Activator.CreateInstance(Require(asm, "Pco.Tests.Fixture.Renamed"))!;
            Equal(Require(asm, "Pco.Tests.Fixture.Renamed").GetMethod("Bump")!.Invoke(renamed, null),
                "https://api.potatotv.asia/v1/report1", "私有静态调用按原样保留，静态 Concat 经代理后结果不变");

            object child = Activator.CreateInstance(Require(asm, "Pco.Tests.Fixture.RenamedChild"))!;
            Equal(Require(asm, "Pco.Tests.Fixture.RenamedChild").GetMethod("Score")!.Invoke(child, null),
                1, "base 调用与虚方法分派经代理后仍正确");

            Type guarded = Require(asm, "Pco.Tests.Fixture.Guarded");
            Equal(guarded.GetMethod("Run")!.Invoke(null, [false]), "guard-ok-token", "try 块内调用经代理后正常");
            Equal(guarded.GetMethod("Run")!.Invoke(null, [true]), "guard-caught:guard-fail-token",
                "catch 块内调用经代理后正常");
            Equal(guarded.GetField("Done")!.GetValue(null), 2, "finally 仍执行两次");

            Type flat = Require(asm, "Pco.Tests.Fixture.Flattened");
            Equal(flat.GetMethod("Sum")!.Invoke(null, [new[] { 1, 2, -3, 4 }, 100]), 7, "循环方法经代理后结果不变");
        }
        catch (Exception ex)
        {
            Failures.Add($"引用代理抛出异常：{ex}");
        }
        finally
        {
            alc.Unload();
            TryDelete(dir);
        }
    }

    /// <summary>
    /// 全量流水线：字符串加密 + 反调试 + 完整性校验 + 引用代理 + 控制流平坦化一次叠满。
    /// 验证五个变换能叠着用、能加载执行，明文消失，尾部哈希自洽，各注入类型齐全。
    /// </summary>
    private static void PipelineSuite()
    {
        string input = typeof(Startup).Assembly.Location;
        string srcDir = Path.GetDirectoryName(input)!;
        string dir = Path.Combine(Path.GetTempPath(), "pco-pipe-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string output = Path.Combine(dir, "Pco.Tests.pipe.dll");
        var alc = new AssemblyLoadContext("pco-pipe-" + Guid.NewGuid().ToString("N"), isCollectible: true);

        try
        {
            var rewriter = new AssemblyRewriter(File.ReadAllBytes(input));
            rewriter.EnableStringEncryption(0x24);
            rewriter.EnableAntiDebug();
            rewriter.EnableIntegrity();
            rewriter.EnableProxy();
            rewriter.EnableControlFlow();
            byte[] rewritten = rewriter.Run();
            File.WriteAllBytes(output, rewritten);

            Check(rewriter.EncryptedStrings > 0, "流水线：字符串被加密");
            Check(rewriter.AntiDebugInjected, "流水线：反调试注入");
            Check(rewriter.IntegrityInjected, "流水线：完整性校验注入");
            Check(rewriter.ProxiedCalls > 0, "流水线：引用代理生效");
            Check(rewriter.FlattenedMethods > 0, "流水线：控制流平坦化生效");

            Check(!ContainsUtf16(rewritten, "https://api.potatotv.asia/v1/report"), "流水线：报告地址明文消失");
            Check(!ContainsUtf16(rewritten, "guard-fail-token"), "流水线：异常文案明文消失");
            Check(!ContainsUtf16(rewritten, "startup-ok"), "流水线：入口字符串明文消失");
            Check(!ContainsUtf16(rewritten, "https://dl.potatotv.asia/files/version.json"),
                "流水线：const 字段的 Constant 行也加密");

            int bodyLength = rewritten.Length - IntegrityCheck.TrailerSize;
            Check(rewritten.AsSpan(bodyLength).SequenceEqual(SHA256.HashData(rewritten.AsSpan(0, bodyLength))),
                "流水线：尾部 SHA-256 自洽");

            Inventory after = InventoryOf(output);
            Check(after.Types.Contains("PaccManager.__Decrypt"), "流水线：__Decrypt 就位");
            Check(after.Types.Contains("PaccManager.__AntiDebug"), "流水线：__AntiDebug 就位");
            Check(after.Types.Contains("PaccManager.__Integrity"), "流水线：__Integrity 就位");
            Check(after.Types.Contains("PaccManager.__Proxy"), "流水线：__Proxy 就位");

            Assembly asm = LoadInto(alc, output, srcDir);
            Equal(Require(asm, "Pco.Tests.Fixture.Startup").GetMethod("OnStartup")!.Invoke(null, null),
                "startup-ok", "流水线：入口方法经注入 + 代理 + 平坦化后仍返回原值");

            Equal(Require(asm, "Pco.Tests.Fixture.ConstantHolder").GetMethod("Read")!.Invoke(null, null),
                "https://dl.potatotv.asia/files/version.json", "流水线：const 用法内联后仍返回原值");

            object renamed = Activator.CreateInstance(Require(asm, "Pco.Tests.Fixture.Renamed"))!;
            Equal(Require(asm, "Pco.Tests.Fixture.Renamed").GetMethod("Bump")!.Invoke(renamed, null),
                "https://api.potatotv.asia/v1/report1", "流水线：加密字符串还原 + 代理调用正确");

            Type guarded = Require(asm, "Pco.Tests.Fixture.Guarded");
            Equal(guarded.GetMethod("Run")!.Invoke(null, [true]), "guard-caught:guard-fail-token",
                "流水线：异常路径字符串还原正确");

            Type flat = Require(asm, "Pco.Tests.Fixture.Flattened");
            Equal(flat.GetMethod("Sum")!.Invoke(null, [new[] { 1, 2, 5, 4 }, 3]), 3, "流水线：平坦化循环结果正确");
            Equal(flat.GetMethod("Count")!.Invoke(null, [10]), 5, "流水线：平坦化 while 结果正确");
        }
        catch (Exception ex)
        {
            Failures.Add($"全量流水线抛出异常：{ex}");
        }
        finally
        {
            alc.Unload();
            TryDelete(dir);
        }
    }

    // ------------------------------------------------------------------
    // 崩溃堆栈还原
    // ------------------------------------------------------------------

    /// <summary>
    /// PcoRetrace 读 mapping 把混淆名换回原名。帧行精确还原「类型.成员」，
    /// 异常头里的类型名按词边界替换，未登记的类名与成员名一律不动。
    /// </summary>
    private static void RetraceSuite()
    {
        string dir = Path.Combine(Path.GetTempPath(), "pco-retrace-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string mappingPath = Path.Combine(dir, "pco-mapping.txt");
        string mappingText = string.Join("\n", new[]
        {
            "PaccManager.Services.ProbeLauncher -> a.b",
            "PaccManager.Services.ProbeLauncher::Start -> a.b::c",
            "PaccManager.Services.ProbeLauncher::Stop -> a.b::d",
            "PaccManager.Services.ProbeLauncher+Inner -> a.b+e",
            "PaccManager.Services.ProbeLauncher+Inner::Run -> a.b+e::f",
            "",
        });

        try
        {
            File.WriteAllText(mappingPath, mappingText, new UTF8Encoding(false));
            PcoRetrace.Mapping mapping = PcoRetrace.LoadMapping(mappingPath);

            string stack = string.Join("\n", new[]
            {
                "System.NullReferenceException: a.b+e 崩溃",
                "   at a.b.c(PaccManager.dll)",
                "   at a.b+e.f()",
                "   at PaccManager.App.Main()",
                "   at a.b.zzz()",
            });
            string restored = mapping.Retrace(stack);

            Check(restored.Contains("   at PaccManager.Services.ProbeLauncher.Start(PaccManager.dll)", StringComparison.Ordinal),
                "retrace：帧行的类型名与成员名一起还原");
            Check(restored.Contains("   at PaccManager.Services.ProbeLauncher+Inner.Run()", StringComparison.Ordinal),
                "retrace：嵌套类型的帧正确还原");
            Check(restored.Contains("System.NullReferenceException: PaccManager.Services.ProbeLauncher+Inner 崩溃", StringComparison.Ordinal),
                "retrace：异常头里的类型名按词边界还原");
            Check(restored.Contains("   at PaccManager.App.Main()", StringComparison.Ordinal),
                "retrace：未登记的类名原样保留");
            Check(restored.Contains("   at PaccManager.Services.ProbeLauncher.zzz()", StringComparison.Ordinal),
                "retrace：登记类型下未登记的成员名保留");
            Equal(restored.Split('\n').Length, stack.Split('\n').Length, "retrace：行数不变");
            Equal(mapping.Retrace(""), "", "retrace：空输入原样返回");
        }
        catch (Exception ex)
        {
            Failures.Add($"retrace 抛出异常：{ex}");
        }
        finally
        {
            TryDelete(dir);
        }
    }

    // ------------------------------------------------------------------
    // 检测命中处置动作
    // ------------------------------------------------------------------

    /// <summary>
    /// 反调试/完整性校验的处置动作可配置：degrade（默认）只置位，exit 额外调用 Environment.Exit。
    /// 用「产物里有没有 Environment.Exit 的成员引用」来区分；不实际触发检测，那样会真的杀掉测试进程。
    /// </summary>
    private static void DetectionActionSuite()
    {
        string input = typeof(Startup).Assembly.Location;
        string dir = Path.Combine(Path.GetTempPath(), "pco-action-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(dir);
        string degradePath = Path.Combine(dir, "Pco.Tests.degrade.dll");
        string exitPath = Path.Combine(dir, "Pco.Tests.exit.dll");

        try
        {
            var degrade = new AssemblyRewriter(File.ReadAllBytes(input));
            degrade.EnableAntiDebug("OnStartup", DetectionAction.Degrade);
            degrade.EnableIntegrity("OnStartup", DetectionAction.Degrade);
            File.WriteAllBytes(degradePath, degrade.Run());

            var exit = new AssemblyRewriter(File.ReadAllBytes(input));
            exit.EnableAntiDebug("OnStartup", DetectionAction.Exit);
            exit.EnableIntegrity("OnStartup", DetectionAction.Exit);
            File.WriteAllBytes(exitPath, exit.Run());

            Check(!HasMemberRef(degradePath, "Environment", "Exit"),
                "degrade 产物不引用 Environment.Exit（只降级不自毁）");
            Check(HasMemberRef(exitPath, "Environment", "Exit"),
                "exit 产物注入了 Environment.Exit 调用");

            Inventory degradeInventory = InventoryOf(degradePath);
            Inventory exitInventory = InventoryOf(exitPath);
            Check(degradeInventory.Types.Contains("PaccManager.__AntiDebug")
                && degradeInventory.Types.Contains("PaccManager.__Integrity"),
                "degrade 产物含反调试与完整性校验注入类型");
            Check(exitInventory.Types.Contains("PaccManager.__AntiDebug")
                && exitInventory.Types.Contains("PaccManager.__Integrity"),
                "exit 产物含反调试与完整性校验注入类型");
        }
        catch (Exception ex)
        {
            Failures.Add($"检测处置动作抛出异常：{ex}");
        }
        finally
        {
            TryDelete(dir);
        }
    }

    private static Assembly LoadInto(AssemblyLoadContext ctx, string path, string probeDir)
    {
        ctx.Resolving += (c, name) =>
        {
            string candidate = Path.Combine(probeDir, name.Name + ".dll");
            return File.Exists(candidate) ? c.LoadFromAssemblyPath(candidate) : null;
        };
        return ctx.LoadFromAssemblyPath(path);
    }

    private static void TryDelete(string dir)
    {
        try
        {
            Directory.Delete(dir, true);
        }
        catch (Exception)
        {
            // 可回收 ALC 的卸载是异步的，文件可能还没释放。
        }
    }

    /// <summary>取指定类型与方法的 IL 字节。</summary>
    private static byte[] MethodIl(string path, string typeFullName, string methodName)
    {
        using var pe = new PEReader(File.OpenRead(path));
        MetadataReader md = pe.GetMetadataReader();
        foreach (TypeDefinitionHandle h in md.TypeDefinitions)
        {
            TypeDefinition td = md.GetTypeDefinition(h);
            string ns = md.GetString(td.Namespace);
            string simple = md.GetString(td.Name);
            if ((ns.Length == 0 ? simple : ns + "." + simple) != typeFullName)
            {
                continue;
            }
            foreach (MethodDefinitionHandle mh in td.GetMethods())
            {
                MethodDefinition m = md.GetMethodDefinition(mh);
                if (md.GetString(m.Name) == methodName)
                {
                    return pe.GetMethodBody(m.RelativeVirtualAddress).GetILBytes() ?? [];
                }
            }
        }
        throw new InvalidOperationException($"找不到 {typeFullName}::{methodName}");
    }

    /// <summary>状态机前导：<c>ldc.i4 0; stloc N; br</c>（0x20 + 4 字节、0xFE0E + 2 字节、0x38）。</summary>
    private static bool IsStateMachine(byte[] il) =>
        il.Length > 10
        && il[0] == 0x20 && il[1] == 0 && il[2] == 0 && il[3] == 0 && il[4] == 0
        && il[5] == 0xFE && il[6] == 0x0E
        && il[9] == 0x38;

    private static bool ContainsUtf16(byte[] haystack, string needle)
    {
        byte[] pattern = Encoding.Unicode.GetBytes(needle);
        for (int i = 0; i + pattern.Length <= haystack.Length; i++)
        {
            int j = 0;
            while (j < pattern.Length && haystack[i + j] == pattern[j])
            {
                j++;
            }
            if (j == pattern.Length)
            {
                return true;
            }
        }
        return false;
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

    /// <summary>产物里是否存在 <c>&lt;parentTypeName&gt;.&lt;memberName&gt;</c> 的成员引用。</summary>
    private static bool HasMemberRef(string path, string parentTypeName, string memberName)
    {
        using var pe = new PEReader(File.OpenRead(path));
        MetadataReader md = pe.GetMetadataReader();
        foreach (MemberReferenceHandle h in md.MemberReferences)
        {
            MemberReference mr = md.GetMemberReference(h);
            if (md.GetString(mr.Name) != memberName || mr.Parent.Kind != HandleKind.TypeReference)
            {
                continue;
            }
            if (md.GetString(md.GetTypeReference((TypeReferenceHandle)mr.Parent).Name) == parentTypeName)
            {
                return true;
            }
        }
        return false;
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