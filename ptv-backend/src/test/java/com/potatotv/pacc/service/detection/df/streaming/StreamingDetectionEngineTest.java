package com.potatotv.pacc.service.detection.df.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * DF §4.1.1 流式检测验收测试（A18 / A19）。
 *
 * <ul>
 *   <li><b>A18</b>：{@link #endToEndLatencyP95BelowTenMilliseconds()} 以真实事件流实测 20000 条事件的
 *       进程内端到端延迟 P95，并断言 {@code P95 < 10ms}（超标即测试失败）；</li>
 *   <li><b>A19</b>：{@link #idleEstimateIsHighWhenPipelineIsQuiet()} 与
 *       {@link #idleEstimateIsFullWithoutAnyEvent()} 断言空闲期 CPU 估计接近零占用；</li>
 *   <li>{@link #incrementalCostDoesNotScaleWithHistory()} 以「2 倍事件量耗时不显著放大」验证特征/窗口
 *       均为 O(1) 更新（无隐藏的历史重算）。</li>
 * </ul>
 */
class StreamingDetectionEngineTest {

    /** 环形缓冲容量（与引擎默认配置一致）。 */
    private static final int RING_CAPACITY = 8192;
    /** 滑动窗口长度。 */
    private static final int WINDOW_SIZE = 256;
    /** 递交给引擎的批大小（贴近端侧一次上报的量级）。 */
    private static final int BATCH_SIZE = 32;

    /** A18：20000 条事件的端到端延迟 P95 必须低于 10ms。 */
    @Test
    void endToEndLatencyP95BelowTenMilliseconds() {
        StreamingDetectionEngine engine = newEngine(RING_CAPACITY);

        // 预热：让 JIT 完成编译，避免把编译期开销算进延迟指标
        int warmupBatches = 160;
        for (int i = 0; i < warmupBatches; i++) {
            engine.ingest(mixedBatch(i * BATCH_SIZE));
        }

        int measuredBatches = 20_000 / BATCH_SIZE;
        long base = 1_000_000_000L;
        for (int i = 0; i < measuredBatches; i++) {
            engine.ingest(mixedBatch(base + (long) i * BATCH_SIZE));
        }

        long expected = (long) (warmupBatches + measuredBatches) * BATCH_SIZE;

        Map<String, Object> metrics = engine.metrics();
        double p95 = ((Number) metrics.get("p95Ms")).doubleValue();
        double p99 = ((Number) metrics.get("p99Ms")).doubleValue();
        long processed = ((Number) metrics.get("eventsProcessed")).longValue();
        long drops = ((Number) metrics.get("ringBufferDrops")).longValue();

        // 真实观测值（报告里引用这一行输出）
        System.out.printf("[A18] eventsProcessed=%d p50=%.3fms p95=%.3fms p99=%.3fms noise=%.3fms drops=%d%n",
                processed, ((Number) metrics.get("p50Ms")).doubleValue(), p95, p99,
                ((Number) metrics.get("maxMs")).doubleValue(), drops);

        assertEquals(expected, processed);
        assertEquals(0L, drops, "批大小远小于容量，不应发生丢弃");
        assertTrue(p95 < 10.0, "A18 失败：流式检测端到端 P95 = " + p95 + "ms，未低于 10ms");
        assertTrue(p99 < 10.0, "P99 亦应低于 10ms，实测 " + p99 + "ms");
    }

    /** 指标面必须包含契约要求的字段与分阶段耗时。 */
    @Test
    void metricsExposeContractFieldsAndStageTimings() {
        StreamingDetectionEngine engine = newEngine(RING_CAPACITY);
        engine.ingest(mixedBatch(0));

        Map<String, Object> metrics = engine.metrics();
        for (String key : List.of("p50Ms", "p95Ms", "p99Ms", "eventsProcessed", "ringBufferDrops",
                "stageTimings", "idleCpuPercentEstimate")) {
            assertNotNull(metrics.get(key), "缺少指标字段：" + key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> stages = (Map<String, Object>) metrics.get("stageTimings");
        for (String stage : List.of("feature", "window", "rule", "ai")) {
            assertNotNull(stages.get(stage), "缺少阶段指标：" + stage);
        }
    }

    /** 无任何事件时必须报告 100% 空闲（事件驱动、无轮询线程）。 */
    @Test
    void idleEstimateIsFullWithoutAnyEvent() {
        StreamingDetectionEngine engine = newEngine(RING_CAPACITY);
        assertEquals(100.0, ((Number) engine.metrics().get("idleCpuPercentEstimate")).doubleValue(), 1e-9);
    }

    /** A19：静默期（两次小批次之间长时间无输入）空闲估计应接近 100%。 */
    @Test
    void idleEstimateIsHighWhenPipelineIsQuiet() throws InterruptedException {
        StreamingDetectionEngine engine = newEngine(RING_CAPACITY);
        engine.ingest(mixedBatch(0));
        Thread.sleep(400);
        engine.ingest(mixedBatch(1000));

        double idle = ((Number) engine.metrics().get("idleCpuPercentEstimate")).doubleValue();
        System.out.printf("[A19] idleCpuPercentEstimate=%.3f%%%n", idle);
        assertTrue(idle > 90.0, "空闲期 CPU 估计应接近 100%，实测 " + idle + "%");
    }

    /**
     * 增量复杂度：事件量翻倍时总耗时不应显著放大（O(1)/事件 ⇒ 线性缩放）。
     * 若特征或窗口退化为「重算历史」，2 倍事件量的耗时会呈超线性增长而击穿阈值。
     */
    @Test
    void incrementalCostDoesNotScaleWithHistory() {
        StreamingDetectionEngine engine = newEngine(RING_CAPACITY);
        // 充分预热
        engine.ingest(mixedBatch(0, 20_000));

        List<StreamEvent> small = mixedBatch(1_000_000L, 50_000);
        List<StreamEvent> large = mixedBatch(2_000_000L, 100_000);

        long t1 = timeIngest(engine, small);
        long t2 = timeIngest(engine, large);
        double ratio = t2 / (double) t1;
        System.out.printf("[O(1)] 50k=%dns 100k=%dns ratio=%.3f%n", t1, t2, ratio);

        // 线性流水线期望 ratio≈2；给 3.5 倍宽容度以吸收 GC / JIT 抖动，仍可击穿二次复杂度
        assertTrue(ratio < 3.5, "增量更新疑似非 O(1)：耗时比 " + ratio);
    }

    /** 环形缓冲：容量向上取整到 2 的幂，溢出即丢弃且累加计数，绝不满时阻塞。 */
    @Test
    void ringBufferDropsOnOverflowWithoutBlocking() {
        LockFreeRingBuffer<String> ring = new LockFreeRingBuffer<>(4);
        assertEquals(4, ring.capacity());
        assertEquals(8, new LockFreeRingBuffer<>(5).capacity());

        assertTrue(ring.offer("a"));
        assertTrue(ring.offer("b"));
        assertTrue(ring.offer("c"));
        assertTrue(ring.offer("d"));
        assertFalse(ring.offer("e"), "满时写入应被丢弃");
        assertEquals(1L, ring.drops());
        assertEquals(4, ring.size());

        assertEquals("a", ring.poll());
        assertEquals("b", ring.poll());
        assertEquals("c", ring.poll());
        assertEquals("d", ring.poll());
        assertNull(ring.poll());
    }

    /** 引擎在缓冲容量不足时：丢弃超量事件、只处理入队成功的事件，且丢弃数可观测。 */
    @Test
    void engineReportsDropsWhenBatchExceedsRingCapacity() {
        StreamingDetectionEngine engine = new StreamingDetectionEngine(
                new FastRuleLayer(), new AiRefinementLayer(), 4, 8, 0.2, 0.6, 0.4);
        List<StreamVerdict> verdicts = engine.ingest(mixedBatch(0, 10));

        Map<String, Object> metrics = engine.metrics();
        assertEquals(4, verdicts.size());
        assertEquals(6L, ((Number) metrics.get("ringBufferDrops")).longValue());
        assertEquals(4L, ((Number) metrics.get("eventsProcessed")).longValue());
        assertEquals(4, engine.windowSize());
    }

    /** 明显超限的连点器事件必须被判为作弊，正常心跳不得误报。 */
    @Test
    void obviousAutoClickerIsDetectedWhileHeartbeatIsNot() {
        StreamingDetectionEngine engine = newEngine(RING_CAPACITY);

        List<StreamVerdict> cheat = engine.ingest(List.of(event("auto_clicker", "high", 16.5, 0L)));
        StreamVerdict cheatVerdict = cheat.get(0);
        assertTrue(cheatVerdict.detected(), "连点器（CPS 16.5 ≥ 14）应判为作弊");
        assertTrue(cheatVerdict.riskScore() >= 0.6, "风险分应达门限，实测 " + cheatVerdict.riskScore());
        assertTrue(cheatVerdict.tier().equals(StreamVerdict.TIER_RULE)
                        || cheatVerdict.tier().equals(StreamVerdict.TIER_RULE_AI),
                "命中层级应为规则或规则+AI，实测 " + cheatVerdict.tier());
        assertFalse(cheatVerdict.reasons().isEmpty(), "判定应带可解释依据");

        List<StreamVerdict> benign = engine.ingest(List.of(event("heartbeat", "low", 0.1, 0L)));
        assertFalse(benign.get(0).detected(), "正常心跳不应误报");
    }

    /** 滑动窗口为 O(1) 增删：窗口填满后长度恒定。 */
    @Test
    void slidingWindowKeepsFixedSize() {
        SlidingWindow window = new SlidingWindow(4);
        for (int i = 0; i < 4; i++) {
            window.add(1.0, i * 1_000_000L);
        }
        assertEquals(4, window.size());
        for (int i = 4; i < 20; i++) {
            window.add(1.0, i * 1_000_000L);
        }
        assertEquals(4, window.size());
        assertEquals(1.0, window.mean(), 1e-12);
        assertEquals(0.0, window.std(), 1e-12);
    }

    /** 增量特征：在线统计与直接计算一致，且键空间有界。 */
    @Test
    void incrementalFeaturesMatchDirectStatistics() {
        IncrementalFeatureExtractor extractor = new IncrementalFeatureExtractor(0.2);
        double[] values = {1.0, 2.0, 3.0, 4.0};
        for (double v : values) {
            extractor.update("x", v);
        }
        assertEquals(2.5, extractor.mean("x"), 1e-12);
        assertEquals(Math.sqrt(1.25), extractor.std("x"), 1e-12);
        assertEquals(4.0, extractor.last("x"), 1e-12);
        assertEquals(1.0, extractor.min("x"), 1e-12);
        assertEquals(4.0, extractor.max("x"), 1e-12);
        assertEquals(4L, extractor.samples("x"));
    }

    // ------------------------------ 辅助 ------------------------------

    private static StreamingDetectionEngine newEngine(int ringCapacity) {
        return new StreamingDetectionEngine(new FastRuleLayer(), new AiRefinementLayer(),
                ringCapacity, WINDOW_SIZE, 0.2, 0.6, 0.4);
    }

    private static long timeIngest(StreamingDetectionEngine engine, List<StreamEvent> events) {
        long start = System.nanoTime();
        engine.ingest(events);
        return System.nanoTime() - start;
    }

    private static StreamEvent event(String type, String severity, double signal, long occurredAt) {
        return new StreamEvent("PTEID-DF-TEST", type, severity, signal,
                Map.of("feature_cps", signal), occurredAt, 0L);
    }

    /** 一批事件：每 10 条混入一条连点器，其余为心跳，用于同时压到规则层与 AI 层。 */
    private static List<StreamEvent> mixedBatch(long baseOccurredAt) {
        return mixedBatch(baseOccurredAt, BATCH_SIZE);
    }

    private static List<StreamEvent> mixedBatch(long baseOccurredAt, int size) {
        List<StreamEvent> out = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            long ts = baseOccurredAt + i;
            if (i % 10 == 0) {
                out.add(event("auto_clicker", "high", 16.5, ts));
            } else {
                out.add(event("heartbeat", "low", 0.1, ts));
            }
        }
        return out;
    }
}