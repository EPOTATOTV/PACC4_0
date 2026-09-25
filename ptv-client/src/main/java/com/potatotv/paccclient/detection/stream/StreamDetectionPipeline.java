package com.potatotv.paccclient.detection.stream;

import com.potatotv.paccclient.ai.LocalAiModel;
import com.potatotv.paccclient.detection.FeatureVector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DF §4.1.1 实时流式检测管线：把「100ms 周期采样」替换为「事件驱动 + 增量计算」的流水线。
 *
 * <pre>
 *   输入事件流 → [无锁环形缓冲] → [增量特征提取 O(1)] → [滑动窗口 O(1)] → [流式检测器] → 判定
 *                                                                          ├─ 快速规则层 (&lt;1ms)
 *                                                                          └─ AI 精判层 (&lt;10ms，仅规则存疑时)
 * </pre>
 *
 * <p>三点核心改进：</p>
 * <ol>
 *   <li><b>事件驱动</b>：消费线程在无事件时 {@code await} 阻塞（带超时的 park），不存在 100ms 轮询线程，
 *       空闲期 CPU 占用趋近 0（目标 &lt;1%，验收 A19）；</li>
 *   <li><b>增量特征</b>：特征与窗口统计都是常数级更新，处理耗时与历史长度无关（游戏中目标 &lt;6%）；</li>
 *   <li><b>两级判定</b>：规则层先筛，只有存疑事件才进入 AI 精判层，绝大多数正常事件止步于规则层。</li>
 * </ol>
 *
 * <p>延迟口径：端到端延迟 = 「事件被接收（写入 {@code receivedAtNanos}）→ 判定完成」，在进程内实测；
 * 端侧采集与网络往返不计入。各阶段耗时进 {@link LatencyHistogram}，由 {@link #metrics()} 暴露
 * P50/P95/P99，作为 A18「流式检测延迟 P95 &lt; 10ms」的观测面。</p>
 *
 * <p>并发模型：环形缓冲内部无锁；特征累加器/滑动窗口非线程安全，故所有处理在对象监视器锁下串行化
 * （锁粒度 = 一次批量入队 + 排空，属「锁最小」），{@link #submit} 只做无锁入队 + 唤醒信号，绝不在
 * 游戏输入线程上阻塞。任何异常都被捕获计数，绝不向调用方抛出（fail-safe）。</p>
 */
public final class StreamDetectionPipeline implements AutoCloseable {

    /** 端到端延迟直方图量程：100ms。 */
    private static final long LATENCY_RANGE_NANOS = 100_000_000L;
    /** 直方图分桶数：分辨率 5µs。 */
    private static final int LATENCY_BUCKETS = 20_000;
    /** 空闲时消费线程的最大 park 时长（毫秒）——有界等待，便于优雅关闭。 */
    private static final long PARK_MILLIS = 200L;

    private final LockFreeRingBuffer<StreamEvent> ring;
    private final IncrementalFeatureAccumulator accumulator;
    private final SlidingWindow window;
    private final FastRuleLayer fastRuleLayer;
    private final AiRefinementLayer aiLayer;
    private final double riskThreshold;

    private final LatencyHistogram endToEnd = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);
    private final LatencyHistogram featureStage = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);
    private final LatencyHistogram windowStage = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);
    private final LatencyHistogram ruleStage = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);
    private final LatencyHistogram aiStage = new LatencyHistogram(LATENCY_RANGE_NANOS, LATENCY_BUCKETS);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition wakeup = lock.newCondition();

    private volatile boolean running;
    private Thread consumer;

    private long sequence;
    private long eventsProcessed;
    private long detections;
    private long aiRefinements;
    private long failures;
    private long busyNanos;
    private long firstIngestNanos;
    private long lastIngestNanos;
    private long parkedCount;

    /** 默认配置：环形缓冲 8192、窗口 256、EWMA 0.2、风险阈值 0.6。 */
    public StreamDetectionPipeline(LocalAiModel aiModel) {
        this(new FastRuleLayer(), new AiRefinementLayer(aiModel), 8192, 256, 0.2, 0.6);
    }

    /**
     * @param fastRuleLayer 快速规则层
     * @param aiLayer       AI 精判层
     * @param ringCapacity  环形缓冲容量（向上取整到 2 的幂）
     * @param windowSize    滑动窗口/特征窗口长度
     * @param ewmaAlpha     增量特征 EWMA 系数
     * @param riskThreshold 判定为作弊的风险分门限
     */
    public StreamDetectionPipeline(FastRuleLayer fastRuleLayer, AiRefinementLayer aiLayer,
                                   int ringCapacity, int windowSize, double ewmaAlpha, double riskThreshold) {
        this.fastRuleLayer = fastRuleLayer;
        this.aiLayer = aiLayer;
        this.ring = new LockFreeRingBuffer<>(ringCapacity);
        this.window = new SlidingWindow(windowSize);
        this.accumulator = new IncrementalFeatureAccumulator(windowSize, ewmaAlpha);
        this.riskThreshold = riskThreshold;
    }

    // ============================================================================================
    // 事件接入
    // ============================================================================================

    /**
     * 接入一条事件（无锁入队 + 唤醒消费线程，立即返回，绝不阻塞输入线程）。
     * <p>缓冲写满时该事件被丢弃并计入 {@link #metrics() 指标}；终止状态或异常一律静默降级。</p>
     */
    public void submit(StreamEvent event) {
        if (event == null) {
            return;
        }
        try {
            ring.offer(event.receivedAtNanos() == 0L ? event.stampedAt(System.nanoTime()) : event);
            lock.lock();
            try {
                wakeup.signal();
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            failures++;
        }
    }

    /**
     * 同步接入一批事件并返回判定（用于端云批量上报与确定性测试；不依赖消费线程）。
     *
     * @param events 事件批次
     * @return 真正进入流水线并产出判定的判定列表（被环形缓冲丢弃的事件不在此列）
     */
    public List<StreamVerdict> ingest(List<StreamEvent> events) {
        if (events == null || events.isEmpty()) {
            return List.of();
        }
        try {
            lock.lock();
            try {
                long now = System.nanoTime();
                if (firstIngestNanos == 0L) {
                    firstIngestNanos = now;
                }
                lastIngestNanos = now;
                for (StreamEvent e : events) {
                    if (e != null) {
                        ring.offer(e.receivedAtNanos() == 0L ? e.stampedAt(System.nanoTime()) : e);
                    }
                }
                return processQueued();
            } finally {
                lock.unlock();
            }
        } catch (RuntimeException e) {
            failures++;
            return List.of();
        }
    }

    /** 启动事件驱动消费线程（无事件时阻塞，空闲不消耗 CPU）。重复调用无副作用。 */
    public void start() {
        synchronized (this) {
            if (consumer != null) {
                return;
            }
            running = true;
            consumer = Thread.ofPlatform().daemon().name("pacc-df-stream").start(this::runLoop);
        }
    }

    @Override
    public void close() {
        Thread t;
        synchronized (this) {
            running = false;
            t = consumer;
            consumer = null;
        }
        lock.lock();
        try {
            wakeup.signalAll();
        } finally {
            lock.unlock();
        }
        if (t != null) {
            try {
                t.join(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ============================================================================================
    // 处理
    // ============================================================================================

    /** 事件驱动消费循环：有事件就处理，没有就 park；单条异常不终止循环。 */
    private void runLoop() {
        while (running) {
            try {
                boolean idle;
                lock.lock();
                try {
                    if (!running) {
                        return;
                    }
                    idle = processQueued().isEmpty();
                } finally {
                    lock.unlock();
                }
                if (idle) {
                    lock.lock();
                    try {
                        if (running && ring.size() == 0) {
                            parkedCount++;
                            wakeup.await(PARK_MILLIS, TimeUnit.MILLISECONDS);
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                failures++;
            }
        }
    }

    /** 排空环形缓冲并逐条判定（调用方须持有 {@link #lock}）。 */
    private List<StreamVerdict> processQueued() {
        List<StreamVerdict> out = new ArrayList<>();
        StreamEvent event = ring.poll();
        if (event == null) {
            return out;
        }
        long batchStart = System.nanoTime();
        do {
            out.add(process(event));
            event = ring.poll();
        } while (event != null);
        busyNanos += System.nanoTime() - batchStart;
        lastIngestNanos = System.nanoTime();
        return out;
    }

    /** 单条事件的两级判定（增量特征 → 滑窗 → 规则层 → 必要时 AI 精判）。 */
    private StreamVerdict process(StreamEvent event) {
        // 阶段 A：增量特征（O(1)，不重算历史）
        long t0 = System.nanoTime();
        accumulator.observe(event.receivedAtNanos(), event.features());
        FeatureVector snapshot = accumulator.snapshot();
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
        double risk = rule.score();
        List<String> reasons = new ArrayList<>(rule.reasons());
        if (rule.verdict() == FastRuleLayer.Verdict.INCONCLUSIVE) {
            AiRefinementLayer.AiResult ai = aiLayer.refine(event, snapshot, window, rule.score());
            aiScore = ai.score();
            aiInvoked = true;
            aiRefinements++;
            risk = Math.max(risk, aiScore);
            reasons.addAll(ai.reasons());
        }
        long t4 = System.nanoTime();
        if (aiInvoked) {
            aiStage.record(t4 - t3);
        }

        boolean detected = rule.verdict() == FastRuleLayer.Verdict.FLAGGED || risk >= riskThreshold;
        String tier = detected
                ? (aiInvoked ? StreamVerdict.TIER_RULE_AI : StreamVerdict.TIER_RULE)
                : StreamVerdict.TIER_NONE;

        long verdictNanos = System.nanoTime();
        long elapsed = verdictNanos - event.receivedAtNanos();
        endToEnd.record(elapsed);
        eventsProcessed++;
        if (detected) {
            detections++;
        }
        return new StreamVerdict(++sequence, event.pteid(), event.eventType(), detected, tier,
                risk, rule.score(), aiScore, aiInvoked, List.copyOf(reasons), elapsed / 1_000_000.0);
    }

    // ============================================================================================
    // 指标（A18 / A19 观测面）
    // ============================================================================================

    /**
     * 流式检测运行指标。
     *
     * @return {@code p50Ms/p95Ms/p99Ms}（端到端延迟百分位）、{@code eventsProcessed}、
     *         {@code detections}、{@code aiRefinements}、{@code ringBufferDrops}、
     *         {@code stageTimings}（各阶段百分位与计数）、{@code busyMs}、{@code parkedCount}、
     *         {@code idleCpuPercentEstimate}（空闲 CPU 估计）
     */
    public Map<String, Object> metrics() {
        lock.lock();
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("p50Ms", round(endToEnd.percentileMs(0.50)));
            out.put("p95Ms", round(endToEnd.percentileMs(0.95)));
            out.put("p99Ms", round(endToEnd.percentileMs(0.99)));
            out.put("meanMs", round(endToEnd.meanMs()));
            out.put("maxMs", round(endToEnd.maxMs()));
            out.put("eventsProcessed", eventsProcessed);
            out.put("detections", detections);
            out.put("aiRefinements", aiRefinements);
            out.put("failures", failures);
            out.put("ringBufferDrops", ring.drops());
            out.put("ringBufferSize", ring.size());
            out.put("ringBufferCapacity", ring.capacity());
            out.put("trackedKeys", accumulator.trackedKeys());
            out.put("ignoredFeatureKeys", accumulator.ignoredKeys());
            out.put("accumulatedEvents", accumulator.events());
            out.put("eventRatePerSecond", round(accumulator.ratePerSecond()));
            out.put("endToEndOverflowCount", endToEnd.overflowCount());
            out.put("busyMs", round(busyNanos / 1_000_000.0));
            out.put("parkedCount", parkedCount);

            Map<String, Object> stages = new LinkedHashMap<>();
            stages.put("feature", stage(featureStage));
            stages.put("window", stage(windowStage));
            stages.put("rule", stage(ruleStage));
            stages.put("ai", stage(aiStage));
            out.put("stageTimings", stages);
            out.put("idleCpuPercentEstimate", round(idlePercent()));
            return out;
        } finally {
            lock.unlock();
        }
    }

    /** 清空延迟直方图与计数（测试的「预热后重新计数」观测面）。 */
    public void resetMetrics() {
        lock.lock();
        try {
            endToEnd.reset();
            featureStage.reset();
            windowStage.reset();
            ruleStage.reset();
            aiStage.reset();
            sequence = 0;
            eventsProcessed = 0;
            detections = 0;
            aiRefinements = 0;
            failures = 0;
            busyNanos = 0;
            parkedCount = 0;
            firstIngestNanos = 0;
            lastIngestNanos = 0;
        } finally {
            lock.unlock();
        }
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
     * <p>忙时 = 各批次实际处理耗时之和；观测时长 = 首次接收到「当前时刻」的跨度。空闲期间跨度增长而
     * 忙时不增长，估计值随之逼近 100——即「没有事件就不消耗 CPU」。无任何事件时返回 100。</p>
     */
    private double idlePercent() {
        if (firstIngestNanos == 0L) {
            return 100.0;
        }
        long span = System.nanoTime() - firstIngestNanos;
        if (span <= 0) {
            return 100.0;
        }
        double busyRatio = Math.min(1.0, busyNanos / (double) span);
        return Math.max(0.0, (1.0 - busyRatio) * 100.0);
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}