package com.potatotv.pacc.rule;

import com.potatotv.prl.profiler.PrlProfiler;
import com.potatotv.prl.profiler.ProfilerReport;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则引擎与性能采样器的接线（设计文档 §2.15.2）。
 *
 * <p>要钉的是「面板上的数字到底从哪来」：{@link PrlProfiler} 必须真的挂在规则执行路径上、
 * 装载规则时要把版本号与静态内存估算登记进去。这两条任一断掉，管理端的性能面板不会报错，
 * 只会安静地一直显示 0 —— 那是最难发现的一类失效。</p>
 */
class PrlRuleEngineProfilerTest {

    private static final String RULE = "account_history";

    @Test
    void 规则执行被计入采样且登记了版本与静态内存基线() {
        PrlRuleEngine engine = new PrlRuleEngine(true, 15.0);

        engine.evaluate(ctx(70));
        engine.evaluate(ctx(70));

        PrlProfiler profiler = engine.profiler();
        assertEquals(2, profiler.executions(RULE), "两次求值应当记两次");

        ProfilerReport report = profiler.report(RULE);
        assertEquals("1.0.0", report.version(), "版本号取自规则元数据，面板标题要用它");
        assertTrue(report.memoryPeakBytes() > 0, "装载时要登记静态内存估算，否则内存峰值恒为 0");
        assertTrue(report.averageNanos() > 0, "采样到的耗时应当是非零值");
    }

    @Test
    void 没跑过的规则采样为空而非报错() {
        PrlRuleEngine engine = new PrlRuleEngine(true, 15.0);

        assertEquals(0, engine.profiler().executions(RULE));
        ProfilerReport report = engine.profiler().report(RULE);
        assertEquals(0, report.executions());
        assertEquals(0L, report.averageNanos());
    }

    private static Map<String, Object> ctx(int reputation) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("reputation", reputation);
        return ctx;
    }
}