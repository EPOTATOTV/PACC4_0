package com.potatotv.paccclient.detection.stream;

import com.potatotv.paccclient.ai.LocalAiModel;
import com.potatotv.paccclient.detection.FeatureSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4.1.1 流式检测管线验收测试（A18 延迟 / A19 空闲 CPU）：真实管线（环形缓冲 → 增量特征 → 滑动窗口
 * → 两级判定）喂入合成事件，实测端到端延迟 P95 并打印；并验证两级判定的短路行为与「无事件即 park」。
 */
class StreamDetectionPipelineTest {

    private static final String[] FEATURE_KEYS = {
            FeatureSchema.keys().get(0), FeatureSchema.keys().get(1), FeatureSchema.keys().get(2)
    };

    private static StreamEvent event(String type, String severity, double signal, long sequence) {
        Map<String, Double> features = new LinkedHashMap<>();
        features.put(FEATURE_KEYS[0], signal);
        features.put(FEATURE_KEYS[1], signal * 0.5);
        features.put(FEATURE_KEYS[2], (double) (sequence % 7));
        return new StreamEvent("PT0000000001", type, severity, signal, features, sequence, 0L);
    }

    private static double num(Map<String, Object> metrics, String key) {
        return ((Number) metrics.get(key)).doubleValue();
    }

    @Test
    void p95EndToEndLatencyStaysBelowTenMilliseconds() {
        StreamDetectionPipeline pipeline = new StreamDetectionPipeline(new LocalAiModel());

        // 预热：让 JIT / 类加载 / 直方图分桶先热起来（预热样本不计入验收口径）
        List<StreamEvent> warmup = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            warmup.add(event("script", "low", 0.2, i + 1L));
        }
        pipeline.ingest(warmup);
        pipeline.resetMetrics();

        // 测量：2000 条合成事件逐条穿过真实管线
        for (int i = 0; i < 2000; i++) {
            pipeline.ingest(List.of(event("script", "low", 0.2, 10_000L + i)));
        }

        Map<String, Object> m = pipeline.metrics();
        double p50 = num(m, "p50Ms");
        double p95 = num(m, "p95Ms");
        double p99 = num(m, "p99Ms");
        System.out.println("[A18] 流式检测端到端延迟: P50=" + p50 + "ms P95=" + p95 + "ms P99=" + p99
                + "ms 样本=" + m.get("eventsProcessed") + " 丢弃=" + m.get("ringBufferDrops"));

        assertEquals(2000L, ((Number) m.get("eventsProcessed")).longValue());
        assertEquals(0L, ((Number) m.get("ringBufferDrops")).longValue(), "容量充足时不得丢弃事件");
        assertTrue(p95 < 10.0, "A18 要求 P95 < 10ms，实测 " + p95 + "ms");
    }

    @Test
    void obviousSignalIsFlaggedByRuleLayerWithoutAiRefinement() {
        StreamDetectionPipeline pipeline = new StreamDetectionPipeline(new LocalAiModel());

        List<StreamVerdict> verdicts = pipeline.ingest(List.of(event("auto_clicker", "high", 30.0, 1L)));

        assertEquals(1, verdicts.size());
        StreamVerdict v = verdicts.get(0);
        assertTrue(v.detected(), "信号超限应被快速规则层直接判定");
        assertFalse(v.aiInvoked(), "明显异常应短路，不触发 AI 精判层");
        assertEquals(StreamVerdict.TIER_RULE, v.tier());
        assertEquals(0L, ((Number) pipeline.metrics().get("aiRefinements")).longValue());
    }

    @Test
    void inconclusiveEventFallsThroughToAiRefinementLayer() {
        StreamDetectionPipeline pipeline = new StreamDetectionPipeline(new LocalAiModel());

        // script 上限 0.8、信号 0.6、high → 规则分 0.45 落入 [0.4,0.6) 存疑区间
        StreamVerdict v = pipeline.ingest(List.of(event("script", "high", 0.6, 1L))).get(0);

        assertTrue(v.aiInvoked(), "存疑事件必须进入 AI 精判层");
        assertEquals(1L, ((Number) pipeline.metrics().get("aiRefinements")).longValue());
        // 未加载模型 → AI 回退，不抬升分数、不误判
        assertFalse(v.detected());
        assertEquals(StreamVerdict.TIER_NONE, v.tier());
    }

    @Test
    void idleLoopParksInsteadOfPolling() throws Exception {
        StreamDetectionPipeline pipeline = new StreamDetectionPipeline(new LocalAiModel());
        pipeline.start();
        try {
            for (int i = 0; i < 200; i++) {
                pipeline.submit(event("script", "low", 0.2, i + 1L));
            }
            long deadline = System.currentTimeMillis() + 2000;
            while (num(pipeline.metrics(), "eventsProcessed") < 200 && System.currentTimeMillis() < deadline) {
                Thread.sleep(5);
            }
            assertEquals(200.0, num(pipeline.metrics(), "eventsProcessed"));

            double busyBefore = num(pipeline.metrics(), "busyMs");
            Thread.sleep(200);
            double busyAfter = num(pipeline.metrics(), "busyMs");
            double idle = num(pipeline.metrics(), "idleCpuPercentEstimate");

            assertEquals(busyBefore, busyAfter, 1e-9, "空闲期不得产生忙时（不允许 100ms 轮询空转）");
            assertTrue(idle > 90.0, "A19 空闲 CPU 估计应接近 100%，实测 " + idle);
            System.out.println("[A19] 空闲 CPU 估计=" + idle + "% 忙时=" + busyAfter + "ms 空转 park 次数="
                    + pipeline.metrics().get("parkedCount"));
        } finally {
            pipeline.close();
        }
    }
}