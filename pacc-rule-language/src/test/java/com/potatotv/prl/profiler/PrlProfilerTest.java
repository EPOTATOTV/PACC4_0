package com.potatotv.prl.profiler;

import com.potatotv.prl.engine.RuleManager;
import com.potatotv.prl.sandbox.PrlHostContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Profiler 测试（设计文档 §2.15.2）。
 *
 * <p>耗时数字本身不可能断言精确值，所以这里断言的都是<strong>关系</strong>：P95 ≤ P99、
 * 热点占比之和为 1、热点函数等于源里真正被调用的那几个。这些关系一旦写错，
 * 报告读起来会自相矛盾（比如占比加起来 130%），比数字不准更难发现。</p>
 */
class PrlProfilerTest {

    private static final int RUNS = 2000;

    private static String source(String ruleName) {
        return "rule \"" + ruleName + "\" {\n"
                + "    version: \"1.2.0\"\n"
                + "    input {\n"
                + "        events: list[int]\n"
                + "        threshold: int\n"
                + "    }\n"
                + "    let total = sum(events)\n"
                + "    when:\n"
                + "        total > threshold\n"
                + "    then:\n"
                + "        emit_alert(type = \"HOT\", confidence = 0.5, evidence = {\"total\": total})\n"
                + "}\n";
    }

    private static Map<String, Object> input() {
        return Map.of("events", List.of(10L, 20L), "threshold", 5L);
    }

    /** 起一个挂了 Profiler 的引擎，跑 {@code runs} 次。 */
    private static PrlProfiler profile(String ruleName, int runs) {
        PrlProfiler profiler = new PrlProfiler();
        profiler.register(ruleName, "1.2.0", 4096);
        RuleManager manager = new RuleManager(PrlHostContext.EMPTY, profiler);
        manager.loadRule(ruleName, source(ruleName));
        for (int i = 0; i < runs; i++) {
            manager.executeAll(input());
        }
        return profiler;
    }

    // ------------------------------------------------------------------ 计数与分位

    @Test
    void countsEveryExecutionAndReportsLatency() {
        ProfilerReport report = profile("hot", RUNS).report("hot");

        assertEquals(RUNS, report.executions());
        assertTrue(report.averageNanos() > 0, "平均耗时应为正数");
        assertTrue(report.maxNanos() > 0);
        // 分位是只增不减的：P95 的排名永远不低于平均所在的位置，P99 又不低于 P95。
        assertTrue(report.p99Nanos() >= report.p95Nanos(), report.text());
        assertTrue(report.p95Nanos() > 0);
        // 桶上界最多比真实值高 12.5%，留一倍余量做粗校验：分位不该跑到最大值的天上。
        assertTrue(report.p99Nanos() <= report.maxNanos() * 2, report.text());
    }

    @Test
    void reportTextFollowsTheDocumentedShape() {
        String text = profile("hot", RUNS).report("hot").text();

        assertTrue(text.contains("规则: hot v1.2.0"), text);
        assertTrue(text.contains("执行次数: 2,000"), text);
        assertTrue(text.contains("平均执行时间: "), text);
        assertTrue(text.contains("P95: "), text);
        assertTrue(text.contains("P99: "), text);
        assertTrue(text.contains("最大: "), text);
        assertTrue(text.contains("内存峰值: 4KB"), text);
        assertTrue(text.contains("热点函数:"), text);
        assertTrue(text.contains("优化建议:"), text);
    }

    // ------------------------------------------------------------------ 热点

    @Test
    void hotspotsAreTheFunctionsThatActuallyRan() {
        ProfilerReport report = profile("hot", RUNS).report("hot");
        List<FunctionHotspot> hotspots = report.hotspots();

        Map<String, FunctionHotspot> byName = hotspots.stream()
                .collect(Collectors.toMap(FunctionHotspot::name, hotspot -> hotspot));
        assertEquals(Set.of("sum", "emit_alert"), byName.keySet(),
                "热点表应当正好是规则里调用的两个标准库函数");

        // sum 在 let 里每次执行调用一次，emit_alert 在 when 成立时调用一次。
        assertEquals(RUNS, byName.get("sum").calls());
        assertEquals(RUNS, byName.get("emit_alert").calls());

        double shareSum = hotspots.stream().mapToDouble(FunctionHotspot::share).sum();
        assertEquals(1.0, shareSum, 1e-9, "占比之和必须是 100%，否则报告里的百分数会自相矛盾");
    }

    @Test
    void hotspotListIsSortedBySelfTimeDescending() {
        List<FunctionHotspot> hotspots = profile("hot", RUNS).report("hot").hotspots();
        for (int i = 1; i < hotspots.size(); i++) {
            assertTrue(hotspots.get(i - 1).selfNanos() >= hotspots.get(i).selfNanos(),
                    () -> "热点没有按自身耗时降序：" + hotspots);
        }
    }

    @Test
    void suggestionsAreProducedForARealHotspot() {
        ProfilerReport report = profile("hot", RUNS).report("hot");
        assertFalse(report.suggestions().isEmpty(), "有热点却没有任何建议");
    }

    // ------------------------------------------------------------------ 内存与隔离

    @Test
    void memoryPeakComesFromRegistrationAndObservedValues() {
        PrlProfiler profiler = new PrlProfiler();
        profiler.register("hot", "1.2.0", 4096);
        assertEquals(4096, profiler.report("hot").memoryPeakBytes(), "登记的静态估算就是基线");

        profiler.markMemory("hot", 8192);
        assertEquals(8192, profiler.report("hot").memoryPeakBytes());
        profiler.markMemory("hot", 1024);
        assertEquals(8192, profiler.report("hot").memoryPeakBytes(), "观测值只抬峰值，不往下降");
    }

    @Test
    void functionTimeIsAttributedToTheRuleThatRan() {
        PrlProfiler profiler = new PrlProfiler();
        profiler.register("hot", "1.2.0", 0);
        profiler.register("cold", "1.2.0", 0);
        RuleManager manager = new RuleManager(PrlHostContext.EMPTY, profiler);
        manager.loadRule("hot", source("hot"));
        manager.loadRule("cold", source("cold"));
        for (int i = 0; i < 10; i++) {
            manager.executeRule("hot", input());
        }

        assertEquals(10, profiler.report("hot").executions());
        // 并发执行时耗时按线程归属，没跑过的规则不能凭空出现热点。
        assertEquals(0, profiler.report("cold").executions());
        assertTrue(profiler.report("cold").hotspots().isEmpty());
        assertEquals(List.of("cold", "hot"), profiler.rules());
    }

    @Test
    void unregisteredRuleGivesAnEmptyReport() {
        ProfilerReport report = new PrlProfiler().report("never_ran");
        assertEquals(0, report.executions());
        assertEquals(0, report.averageNanos());
        assertTrue(report.hotspots().isEmpty());
        assertTrue(report.text().contains("没有函数级采样"));
    }

    @Test
    void resetDropsCollectedData() {
        PrlProfiler profiler = profile("hot", 5);
        assertFalse(profiler.rules().isEmpty());
        profiler.reset();
        assertTrue(profiler.rules().isEmpty());
        assertEquals(0, profiler.report("hot").executions());
    }
}