package com.potatotv.pacc.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PRL 动态规则引擎测试（设计文档 §3.1.3 步骤 5「双引擎并行验证」的落地形式）。
 *
 * <p>期望值不是手算的：先用迁移前的 8 条 {@code .lua} 规则在 LuaJ 上真跑一遍，
 * 把 28 组上下文的结果（命中规则 / 加分 / 原因 / 封顶后的 bonus）录下来，再拿 PRL 版本逐条比对。
 * 之所以要这么做，是因为 Lua 有几处「看起来像 bug、但确实是当前线上口径」的地方：
 * {@code ctx.signature_hit} 是空串而不是 nil，所以 {@code or "unknown"} 从来没生效过；
 * 信誉 70 时 {@code 30/100} 落在 0.3 这一侧。靠读代码推这些细节一定会错。</p>
 *
 * <p>本轮替换是一次性切换，没有保留 Lua 依赖，所以对照表以常量形式钉在这里 —— 它同时也是
 * 后续改规则时的回归基线：任何一条规则的分值或文案变了，这个测试都会红。</p>
 */
class PrlRuleEngineTest {

    private static final double MAX_BONUS = 15.0;
    private static final double DELTA = 1e-9;

    private PrlRuleEngine engine;

    @BeforeEach
    void setUp() {
        engine = new PrlRuleEngine(true, MAX_BONUS);
    }

    @Test
    void 八条规则全部装载且元数据可读() {
        assertEquals(8, engine.reload());
        List<Map<String, Object>> rules = engine.list();
        assertEquals(8, rules.size());
        for (Map<String, Object> rule : rules) {
            assertTrue((Boolean) rule.get("enabled"), "规则应处于启用态：" + rule);
            assertEquals("1.0.0", rule.get("version"), "规则版本：" + rule);
        }
        Map<String, Object> history = rules.stream()
                .filter(r -> "account_history".equals(r.get("id")))
                .findFirst()
                .orElseThrow();
        assertEquals("account_history.prl", history.get("file"));
        assertEquals("账号历史劣迹", history.get("name"));
    }

    @Test
    void 关掉引擎时不产生任何命中() {
        PrlRuleEngine disabled = new PrlRuleEngine(false, MAX_BONUS);
        PrlRuleEngine.Evaluation ev = disabled.evaluate(ctx("killaura", "critical", 100, "JAVA", "", "", "", "", 0));
        assertTrue(ev.hits().isEmpty());
        assertEquals(0.0, ev.bonus(), DELTA);
        assertEquals(0, ev.ruleCount());
    }

    @Test
    void 迁移后与Lua金标逐条一致() {
        for (GoldenCase golden : goldenCases()) {
            PrlRuleEngine.Evaluation ev = engine.evaluate(golden.ctx());
            assertEquals(golden.bonus(), ev.bonus(), DELTA, "[" + golden.name() + "] 封顶后的 bonus 不一致");
            assertEquals(golden.hits().size(), ev.hits().size(), "[" + golden.name() + "] 命中条数不一致：" + ev.hits());
            for (ExpectedHit expected : golden.hits()) {
                Optional<PrlRuleEngine.RuleHit> actual = ev.hits().stream()
                        .filter(hit -> hit.id().equals(expected.id()))
                        .findFirst();
                assertTrue(actual.isPresent(), "[" + golden.name() + "] 未命中 " + expected.id() + "：" + ev.hits());
                assertEquals(expected.score(), actual.get().score(), DELTA,
                        "[" + golden.name() + "] " + expected.id() + " 加分不一致");
                assertEquals(expected.reason(), actual.get().reason(),
                        "[" + golden.name() + "] " + expected.id() + " 原因文案不一致");
            }
        }
    }

    @Test
    void 空上下文不抛异常且无命中() {
        PrlRuleEngine.Evaluation ev = engine.evaluate(Map.of());
        assertTrue(ev.hits().isEmpty(), "空上下文不应命中任何规则：" + ev.hits());
        assertEquals(0.0, ev.bonus(), DELTA);
        // 空上下文下仍然逐条求值了全部规则
        assertEquals(8, ev.ruleCount());
    }

    // ------------------------------------------------------------------ 金标对照表

    /** 与 {@code RiskScoringService.contextOf} 同构的事件上下文。 */
    private static Map<String, Object> ctx(String eventType, String severity, int clientRisk,
                                           String edition, String processName, String memoryRegion,
                                           String signatureHit, String detail, int reputation) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("pteid", "PTE1234567890");
        ctx.put("event_type", eventType);
        ctx.put("severity", severity);
        ctx.put("client_risk", clientRisk);
        ctx.put("edition", edition);
        ctx.put("process_name", processName);
        ctx.put("memory_region", memoryRegion);
        ctx.put("signature_hit", signatureHit);
        ctx.put("detail", detail);
        ctx.put("reputation", reputation);
        ctx.put("history_factor", Math.max(0.0, 1.0 - reputation / 100.0));
        return ctx;
    }

    private static GoldenCase golden(String name, Map<String, Object> ctx, double bonus, ExpectedHit... hits) {
        return new GoldenCase(name, ctx, bonus, List.of(hits));
    }

    private static ExpectedHit hit(String id, double score, String reason) {
        return new ExpectedHit(id, score, reason);
    }

    /** 28 组上下文，期望值全部来自迁移前 Lua 引擎的真实输出。 */
    private static List<GoldenCase> goldenCases() {
        List<GoldenCase> cases = new ArrayList<>();
        // account_history：>=0.3 命中、信誉 100 不命中、0.31、信誉 0
        cases.add(golden("信誉100不触发历史加成", ctx("killaura", "medium", 10, "JAVA", "", "", "", "", 100), 9.4,
                hit("combat_assist", 9.4, "战斗辅助特征: killaura")));
        cases.add(golden("信誉70恰好跨过0.3阈值", ctx("killaura", "medium", 10, "JAVA", "", "", "", "", 70), 11.8,
                hit("account_history", 2.4, "账号历史劣迹加成"),
                hit("combat_assist", 9.4, "战斗辅助特征: killaura")));
        cases.add(golden("信誉69", ctx("killaura", "medium", 10, "JAVA", "", "", "", "", 69), 11.88,
                hit("account_history", 2.48, "账号历史劣迹加成"),
                hit("combat_assist", 9.4, "战斗辅助特征: killaura")));
        cases.add(golden("信誉0", ctx("killaura", "medium", 10, "JAVA", "", "", "", "", 0), MAX_BONUS,
                hit("account_history", 8.0, "账号历史劣迹加成"),
                hit("combat_assist", 9.4, "战斗辅助特征: killaura")));
        // usb_device
        cases.add(golden("USB宏设备", ctx("usb_device", "medium", 20, "BEDROCK", "", "", "", "{macro:true}", 100), 11.0,
                hit("usb_device", 11.0, "检测到可疑 USB 外设")));
        cases.add(golden("USB脚本设备大小写不敏感", ctx("usb_device", "low", 0, "BEDROCK", "", "", "", "SIMPLE HID script", 100), 11.0,
                hit("usb_device", 11.0, "检测到可疑 USB 外设")));
        cases.add(golden("USB无特征", ctx("usb_device", "low", 0, "BEDROCK", "", "", "", "", 100), 5.0,
                hit("usb_device", 5.0, "检测到可疑 USB 外设")));
        // java_mod / injection
        cases.add(golden("Java版Mod命中特征", ctx("java_mod", "high", 50, "JAVA", "", "", "CheatMod.jar", "", 100), 15.0,
                hit("java_mod", 15.0, "Java 版 Mod 篡改: CheatMod.jar")));
        cases.add(golden("注入事件无特征基岩版", ctx("injection", "high", 50, "BEDROCK", "", "", "", "", 100), 9.0,
                hit("java_mod", 9.0, "Java 版 Mod 篡改: ")));
        cases.add(golden("注入事件无特征Java版", ctx("injection", "low", 0, "JAVA", "", "", "", "", 100), 11.0,
                hit("java_mod", 11.0, "Java 版 Mod 篡改: ")));
        // debugger
        cases.add(golden("调试器且端侧高危", ctx("debugger", "high", 70, "JAVA", "x64dbg.exe", "", "", "", 100), 15.0,
                hit("debugger", 15.0, "检测到调试器附加，存在动态分析风险")));
        cases.add(golden("调试器端侧未达阈值", ctx("debugger", "high", 69, "JAVA", "gdb", "", "", "", 100), 12.0,
                hit("debugger", 12.0, "检测到调试器附加，存在动态分析风险")));
        // auto_clicker
        cases.add(golden("连点器端侧极端高危", ctx("autoclicker", "medium", 80, "BEDROCK", "", "", "", "", 100), 14.0,
                hit("auto_clicker", 14.0, "连点频率异常")));
        cases.add(golden("连点器端侧高危未达极端", ctx("autoclicker", "medium", 79, "BEDROCK", "", "", "", "", 100), 9.95,
                hit("auto_clicker", 9.95, "连点频率异常")));
        // combat_assist
        cases.add(golden("杀戮光环critical", ctx("killaura", "critical", 90, "JAVA", "", "", "", "", 100), MAX_BONUS,
                hit("combat_assist", 19.6, "战斗辅助特征: killaura")));
        cases.add(golden("自瞄low", ctx("aimbot", "low", 0, "JAVA", "", "", "", "", 100), 5.0,
                hit("combat_assist", 5.0, "战斗辅助特征: aimbot")));
        cases.add(golden("超距medium", ctx("reach", "medium", 30, "JAVA", "", "", "", "", 100), 10.2,
                hit("combat_assist", 10.2, "战斗辅助特征: reach")));
        cases.add(golden("搭桥high", ctx("scaffold", "high", 40, "BEDROCK", "", "", "", "", 100), 14.6,
                hit("combat_assist", 14.6, "战斗辅助特征: scaffold")));
        cases.add(golden("未知严重度回落7分", ctx("scaffold", "whatever", 0, "BEDROCK", "", "", "", "", 100), 7.0,
                hit("combat_assist", 7.0, "战斗辅助特征: scaffold")));
        cases.add(golden("非战斗事件不触发", ctx("sprint", "high", 40, "BEDROCK", "", "", "", "", 100), 0.0));
        // process_injection
        cases.add(golden("已知注入器大小写不敏感", ctx("process_injection", "critical", 60, "JAVA", "X64DBG.EXE", "", "", "", 100), 15.0,
                hit("process_injection", 15.0, "检测到进程注入: X64DBG.EXE")));
        cases.add(golden("普通进程注入", ctx("process_injection", "critical", 60, "JAVA", "Steam.exe", "", "", "", 100), 10.0,
                hit("process_injection", 10.0, "检测到进程注入: Steam.exe")));
        cases.add(golden("进程名为空", ctx("process_injection", "critical", 60, "JAVA", "", "", "", "", 100), 10.0,
                hit("process_injection", 10.0, "检测到进程注入: ")));
        // memory_tamper
        cases.add(golden("内存篡改critical", ctx("memory_tamper", "critical", 60, "JAVA", "", "0x7FFE0000", "", "", 100), MAX_BONUS,
                hit("memory_tamper", 18.0, "内存区域被篡改: 0x7FFE0000")));
        cases.add(golden("内存篡改low", ctx("memory_tamper", "low", 0, "JAVA", "", "memfd:anon", "", "", 100), 4.0,
                hit("memory_tamper", 4.0, "内存区域被篡改: memfd:anon")));
        cases.add(golden("内存篡改未知严重度回落6分", ctx("memory_tamper", "unknown", 20, "JAVA", "", "", "", "", 100), 7.0,
                hit("memory_tamper", 7.0, "内存区域被篡改: ")));
        // 多规则同时命中
        cases.add(golden("历史加成与战斗辅助叠加封顶", ctx("killaura", "critical", 100, "JAVA", "", "", "KillAura.dll", "", 0), MAX_BONUS,
                hit("account_history", 8.0, "账号历史劣迹加成"),
                hit("combat_assist", 20.0, "战斗辅助特征: killaura")));
        cases.add(golden("历史加成与内存篡改叠加封顶", ctx("memory_tamper", "critical", 100, "JAVA", "", "0x1", "", "", 0), MAX_BONUS,
                hit("account_history", 8.0, "账号历史劣迹加成"),
                hit("memory_tamper", 20.0, "内存区域被篡改: 0x1")));
        return cases;
    }

    private record GoldenCase(String name, Map<String, Object> ctx, double bonus, List<ExpectedHit> hits) {
    }

    private record ExpectedHit(String id, double score, String reason) {
    }
}