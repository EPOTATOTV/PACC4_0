package com.potatotv.pacc.service.detection.df.streaming;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DF §4.1.1 实时流式检测引擎：把「100ms 周期采样」替换为「事件驱动 + 增量计算」的流水线。
 * <pre>
 *   输入事件流 → [无锁环形缓冲] → [增量特征提取 O(1)] → [滑动窗口 O(1)] → [流式检测器] → 判定
 *                                                                          ├─ 快速规则层 (&lt;1ms)
 *                                                                          └─ AI 精判层 (&lt;10ms，仅规则存疑时运行)
 * </pre>
 *
 * <p>三点核心改进：</p>
 * <ol>
 *   <li><b>事件驱动</b>：没有任何后台轮询线程——只有 {@link #ingest} 被调用时才消耗 CPU，
 *       空闲期完全静默（空闲 CPU 目标 &lt;1%）；</li>
 *   <li><b>增量特征</b>：特征与窗口统计都是常数级更新，处理耗时与历史长度无关（游戏中 CPU 目标 &lt;6%）；</li>
 *   <li><b>两级判定</b>：规则层先筛，只有存疑事件才进入 AI 精判层，绝大多数正常事件止步于规则层。</li>
 * </ol>
 *
 * <p>延迟口径：端到端延迟 = 「事件被 {@link #ingest} 接收（写入 {@code receivedAtNanos}）→ 判定完成」，
 * 在进程内实测；端侧采集与网络往返不计入（那属于端侧指标）。所有阶段耗时进 {@link LatencyHistogram}，
 * 由 {@link #metrics()} 暴露 P50/P95/P99，作为 A18「流式检测延迟 P95 &lt; 10ms」的观测面。</p>
 *
 * <p>并发模型：环形缓冲内部无锁，但特征器/窗口非线程安全，故 {@link #ingest} 以对象监视器串行化
 * （锁粒度 = 一次批量入队 + 排空，属「锁最小」）；单进程内多线程上报仍安全。</p>
 */
@Service
public class StreamingDetectionEngine {

    /** 端到端延迟直方图量程：100ms。 */
    private static final long LATENCY_RANGE_NANOS = 100_000_000L;
    /** 直方图分桶数：分辨率 5µs。 */
    private static final int LATENCY_BUCKETS = 20_000;

    private final LockFreeRingBuffer<StreamEvent> ring;
    private final SlidingWindow window;
    private final IncrementalFeatureExtractor extractor;
    private final FastRuleLayer fastRuleLayer;
    private final AiRefinementLayer aiLayer;
    private final double riskThreshold;
    private final double aiTriggerScore;

    private final LatencyHistogram endToEnd = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);
    private final LatencyHistogram featureStage = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);
    private final LatencyHistogram windowStage = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);
    private final LatencyHistogram ruleStage = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);
    private final LatencyHistogram aiStage = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);

    private long sequence;
    private long eventsProcessed;
    private long detections;
    private long aiRefinements;
    private long busyNanos;
    private long firstIngestNanos;
    private long lastIngestNanos;

    /**
     * @param ringCapacity   环形缓冲容量（向上取整到 2 的幂）
     * @param windowSize     滑动窗口长度（向上取整到 2 的幂）
     * @param ewmaAlpha      增量特征 EWMA 系数
     * @param riskThreshold  判定为作弊的风险分门限
     * @param aiTriggerScore 触发 AI 精判层的规则分门限（越低越激进，CPU 越高）
     */
    public StreamingDetectionEngine(FastRuleLayer fastRuleLayer,
                                    AiRefinementLayer aiLayer,
                                    @Value("${pacc.df.stream.ring-capacity:8192}") int ringCapacity,
                                    @Value("${pacc.df.stream.window-size:256}") int windowSize,
                                    @Value("${pacc.df.stream.ewma-alpha:0.2}") double ewmaAlpha,
                                    @Value("${pacc.df.stream.risk-threshold:0.6}") double riskThreshold,
                                    @Value("${pacc.df.stream.ai-trigger-score:0.4}") double aiTriggerScore) {
        this.fastRuleLayer = fastRuleLayer;
        this.aiLayer = aiLayer;
        this.ring = new LockFreeRingBuffer<>(ringCapacity);
        this.window = new SlidingWindow(windowSize);
        this.extractor = new IncrementalFeatureExtractor(ewmaAlpha);
        this.riskThreshold = riskThreshold;
        this.aiTriggerScore = aiTriggerScore;
    }

    /**
     * 接收一批事件并同步完成判定（事件触发，无轮询）。
     *
     * @param events 端侧上报的事件批次
     * @return 真正进入流水线并产出判定的判定列表（被环形缓冲丢弃的事件不在此列）
     */
    public List<StreamVerdict> ingest(List<StreamEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        long batchStart = System.nanoTime();
        synchronized (this) {
            if (firstIngestNanos == 0L) {
                firstIngestNanos = batchStart;
            }
            lastIngestNanos = batchStart;

            // 阶段一：入队（无锁环形缓冲；满则丢弃，绝不阻塞输入流）
            for (StreamEvent event : events) {
                if (event != null) {
                    ring.offer(event.stampedAt(System.nanoTime()));
                }
            }

            // 阶段二：排空并逐条判定
            List<StreamVerdict> verdicts = new ArrayList<>(events.size());
            StreamEvent event;
            while ((event = ring.poll()) != null) {
                verdicts.add(process(event));
            }
            busyNanos += System.nanoTime() - batchStart;
            return verdicts;
        }
    }

    /** 单条事件的两级判定（增量特征 → 滑窗 → 规则层 → 必要时 AI 精判）。 */
    private StreamVerdict process(StreamEvent event) {
        // 阶段 A：增量特征（O(1)，不重算历史）
        long t0 = System.nanoTime();
        extractor.countEvent();
        extractor.update("signal", event.signal());
        extractor.updateAll(event.features());
        long t1 = System.nanoTime();
        featureStage.record(t1 - t0);

        // 阶段 B：滑动窗口（O(1)，加入新值并淘汰最旧值）
        window.add(event.signal(), event.receivedAtNanos());
        long t2 = System.nanoTime();
        windowStage.record(t2 - t1);

        // 阶段 C：快速规则层（<1ms）
        FastRuleLayer.RuleResult rule = fastRuleLayer.evaluate(event, window);
        long t3 = System.nanoTime();
        ruleStage.record(t3 - t2);

        // 阶段 D：AI 精判层（仅规则存疑时运行，<10ms）
        double aiScore = 0.0;
        boolean aiInvoked = false;
        List<String> reasons = new ArrayList<>(rule.reasons());
        if (rule.score() >= aiTriggerScore) {
            AiRefinementLayer.AiResult ai = aiLayer.refine(event, extractor, window, rule.score());
            aiScore = ai.score();
            aiInvoked = true;
            aiRefinements++;
            reasons.addAll(ai.contributions());
        }
        long t4 = System.nanoTime();
        if (aiInvoked) {
            aiStage.record(t4 - t3);
        }

        double risk = Math.max(rule.score(), aiScore);
        boolean detected = risk >= riskThreshold;
        String tier = detected
                ? (aiInvoked ? StreamVerdict.TIER_RULE_AI : StreamVerdict.TIER_RULE)
                : StreamVerdict.TIER_NONE;

        long verdictNanos = System.nanoTime();
        double latencyMs = (verdictNanos - event.receivedAtNanos()) / 1_000_000.0;
        endToEnd.record(verdictNanos - event.receivedAtNanos());
        eventsProcessed++;
        if (detected) {
            detections++;
        }
        return new StreamVerdict(++sequence, event.pteid(), event.eventType(), detected, tier,
                risk, rule.score(), aiScore, aiInvoked, List.copyOf(reasons), latencyMs);
    }

    /**
     * 流式检测运行指标（A18 / A19 的观测面）。
     *
     * @return {@code p50Ms/p95Ms/p99Ms}（端到端延迟百分位）、{@code eventsProcessed}、
     *         {@code ringBufferDrops}、{@code stageTimings}（各阶段百分位与计数）、
     *         {@code idleCpuPercentEstimate}（空闲 CPU 估计）、{@code detections}、{@code aiRefinements}
     */
    public synchronized Map<String, Object> metrics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("p50Ms", round(endToEnd.percentileMs(0.50)));
        out.put("p95Ms", round(endToEnd.percentileMs(0.95)));
        out.put("p99Ms", round(endToEnd.percentileMs(0.99)));
        out.put("meanMs", round(endToEnd.meanMs()));
        out.put("maxMs", round(endToEnd.maxMs()));
        out.put("eventsProcessed", eventsProcessed);
        out.put("detections", detections);
        out.put("ringBufferDrops", ring.drops());
        out.put("ringBufferSize", ring.size());
        out.put("ringBufferCapacity", ring.capacity());
        out.put("aiRefinements", aiRefinements);

        Map<String, Object> stages = new LinkedHashMap<>();
        stages.put("feature", stage(featureStage));
        stages.put("window", stage(windowStage));
        stages.put("rule", stage(ruleStage));
        stages.put("ai", stage(aiStage));
        out.put("stageTimings", stages);
        out.put("idleCpuPercentEstimate", round(idlePercent()));
        return out;
    }

    /** 单个阶段的百分位与计数。 */
    private static Map<String, Object> stage(LatencyHistogram h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("p50Ms", round(h.percentileMs(0.50)));
        m.put("p95Ms", round(h.percentileMs(0.95)));
        m.put("count", h.count());
        return m;
    }

    /**
     * 空闲 CPU 估计 0-100：{@code 1 - 忙时 / 观测时长}。
     *
     * <p>忙时 = 各批次实际处理耗时之和；观测时长 = 首次接收到最近一次接收的跨度。空闲期间跨度增长而
     * 忙时不增长，估计值随之逼近 100——即「没有事件就不消耗 CPU」。无任何事件时返回 100。</p>
     */
    private double idlePercent() {
        long span = lastIngestNanos - firstIngestNanos;
        if (span <= 0) {
            return 100.0;
        }
        double busyRatio = Math.min(1.0, busyNanos / (double) span);
        return Math.max(0.0, (1.0 - busyRatio) * 100.0);
    }

    /** 当前滑动窗口长度（供测试与诊断）。 */
    public synchronized int windowSize() {
        return window.size();
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}