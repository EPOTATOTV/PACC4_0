package com.potatotv.paccclient.apm;

import java.util.concurrent.atomic.LongAdder;

/**
 * 检测链路维度指标采集。
 *
 * <p>检测引擎（{@code DetectionEngine} 及其子模块）不应硬依赖 APM 子系统，否则遥测一旦不可用就会
 * 拖垮检测热路径。因此这里提供一个静态、无锁、零分配的插桩汇聚点 {@link #SINK}：引擎只需在关键
 * 位置调用 {@code DetectionMetrics.SINK.recordXxx(...)}（或 {@link #touchCollection(long)} 等静态
 * 便捷方法），失败与否都不影响检测本身。本次升级不修改 DetectionEngine，SINK 是先落地的公开 API，
 * 后续由引擎按需接入。</p>
 *
 * <p>计数器用 {@link LongAdder}（高并发增量优于 {@link java.util.concurrent.atomic.AtomicLong}）；
 * 每秒采样时对差值求速率/均值，因此上报的是「刚刚过去的一秒」而非累计值（monotonic 计数器除外）。</p>
 */
public final class DetectionMetrics {

    /** 检测引擎插桩汇聚点：全部方法无锁、无阻塞、绝不抛出。 */
    public static final class Sink {

        private final LongAdder collectMs = new LongAdder();
        private final LongAdder collectCount = new LongAdder();
        private final LongAdder detectMs = new LongAdder();
        private final LongAdder detectCount = new LongAdder();
        private final LongAdder aiInferMs = new LongAdder();
        private final LongAdder aiInferCount = new LongAdder();
        private final LongAdder stealthMs = new LongAdder();
        private final LongAdder stealthCount = new LongAdder();
        private final LongAdder events = new LongAdder();
        private final LongAdder positives = new LongAdder();
        private final LongAdder featureDims = new LongAdder();
        private final LongAdder featureBacked = new LongAdder();
        private final LongAdder featureSamples = new LongAdder();
        private final LongAdder modelInfers = new LongAdder();
        private final LongAdder modelCacheHits = new LongAdder();

        Sink() {
        }

        /** 记录一次特征采集耗时（毫秒）。 */
        public void recordCollect(long millis) {
            collectMs.add(Math.max(0, millis));
            collectCount.increment();
        }

        /** 记录一次规则/引擎判定耗时（毫秒）。 */
        public void recordDetect(long millis) {
            detectMs.add(Math.max(0, millis));
            detectCount.increment();
        }

        /** 记录一次端侧模型推理耗时（毫秒）。 */
        public void recordAiInfer(long millis) {
            aiInferMs.add(Math.max(0, millis));
            aiInferCount.increment();
        }

        /** 记录一次隐身探针耗时（毫秒）。 */
        public void recordStealth(long millis) {
            stealthMs.add(Math.max(0, millis));
            stealthCount.increment();
        }

        /** 记录一次检测事件及其是否命中。 */
        public void recordEvent(boolean positive) {
            events.increment();
            if (positive) positives.increment();
        }

        /** 记录特征维度数及其有数据支撑的维度数。 */
        public void recordFeatureDimensions(int dimensions, int backed) {
            int dims = Math.max(0, dimensions);
            featureDims.add(dims);
            featureBacked.add(Math.max(0, Math.min(dims, backed)));
            featureSamples.increment();
        }

        /** 记录一次模型推理及其是否命中缓存。 */
        public void recordModelInfer(boolean cacheHit) {
            modelInfers.increment();
            if (cacheHit) modelCacheHits.increment();
        }
    }

    /** 全局唯一插桩入口。 */
    public static final Sink SINK = new Sink();

    private long lastTickMillis = System.currentTimeMillis();
    private long prevCollectMs;
    private long prevCollectCount;
    private long prevDetectMs;
    private long prevDetectCount;
    private long prevAiMs;
    private long prevAiCount;
    private long prevStealthMs;
    private long prevStealthCount;
    private long prevEvents;
    private long prevPositives;
    private long prevDimSum;
    private long prevBackedSum;
    private long prevFeatureSamples;
    private long prevInfers;
    private long prevCacheHits;

    /** 便捷插桩：特征采集耗时。 */
    public static void touchCollection(long millis) {
        SINK.recordCollect(millis);
    }

    /** 便捷插桩：引擎判定耗时。 */
    public static void touchDetect(long millis) {
        SINK.recordDetect(millis);
    }

    /** 便捷插桩：端侧推理耗时。 */
    public static void touchAiInfer(long millis) {
        SINK.recordAiInfer(millis);
    }

    /** 便捷插桩：隐身探针耗时。 */
    public static void touchStealth(long millis) {
        SINK.recordStealth(millis);
    }

    /** 便捷插桩：检测事件。 */
    public static void touchEvent(boolean positive) {
        SINK.recordEvent(positive);
    }

    /** 便捷插桩：特征维度覆盖。 */
    public static void touchFeatureDimensions(int dimensions, int backed) {
        SINK.recordFeatureDimensions(dimensions, backed);
    }

    /** 便捷插桩：模型推理缓存命中。 */
    public static void touchModelInfer(boolean cacheHit) {
        SINK.recordModelInfer(cacheHit);
    }

    /** 按秒差值导出检测维度指标。任何异常都不得向外传播。 */
    public void collect(ApmSnapshot snapshot) {
        try {
            long now = snapshot.timestamp();
            double seconds = Math.max(0.001, (now - lastTickMillis) / 1000.0);
            lastTickMillis = now;

            long collectMs = SINK.collectMs.sum();
            long collectCount = SINK.collectCount.sum();
            snapshot.with("detect_collect_latency", mean(collectMs - prevCollectMs, collectCount - prevCollectCount));
            prevCollectMs = collectMs;
            prevCollectCount = collectCount;

            long detectMs = SINK.detectMs.sum();
            long detectCount = SINK.detectCount.sum();
            snapshot.with("detect_engine_latency", mean(detectMs - prevDetectMs, detectCount - prevDetectCount));
            prevDetectMs = detectMs;
            prevDetectCount = detectCount;

            long aiMs = SINK.aiInferMs.sum();
            long aiCount = SINK.aiInferCount.sum();
            snapshot.with("detect_ai_infer_latency", mean(aiMs - prevAiMs, aiCount - prevAiCount));
            prevAiMs = aiMs;
            prevAiCount = aiCount;

            long stealthMs = SINK.stealthMs.sum();
            long stealthCount = SINK.stealthCount.sum();
            snapshot.with("detect_stealth_latency", mean(stealthMs - prevStealthMs, stealthCount - prevStealthCount));
            prevStealthMs = stealthMs;
            prevStealthCount = stealthCount;

            long events = SINK.events.sum();
            long positives = SINK.positives.sum();
            long eventDelta = events - prevEvents;
            long positiveDelta = positives - prevPositives;
            snapshot.with("detect_event_rate", Math.max(0, eventDelta) / seconds);
            snapshot.with("detect_positive_rate", eventDelta <= 0 ? 0.0
                    : Math.max(0, Math.min(1.0, positiveDelta / (double) eventDelta)));
            prevEvents = events;
            prevPositives = positives;

            long dimSum = SINK.featureDims.sum();
            long backedSum = SINK.featureBacked.sum();
            long featSamples = SINK.featureSamples.sum();
            long dimDelta = dimSum - prevDimSum;
            long backedDelta = backedSum - prevBackedSum;
            long sampleDelta = featSamples - prevFeatureSamples;
            snapshot.with("detect_feature_dimensions", sampleDelta <= 0 ? 0.0 : Math.max(0, dimDelta) / (double) sampleDelta);
            snapshot.with("detect_feature_backfill_ratio", dimDelta <= 0 ? 0.0
                    : Math.max(0, Math.min(1.0, backedDelta / (double) dimDelta)));
            prevDimSum = dimSum;
            prevBackedSum = backedSum;
            prevFeatureSamples = featSamples;

            long infers = SINK.modelInfers.sum();
            long cacheHits = SINK.modelCacheHits.sum();
            long inferDelta = infers - prevInfers;
            long cacheDelta = cacheHits - prevCacheHits;
            // 单调计数器：上报累计总数（与上报契约里的 type=counter 对应）
            snapshot.with("detect_model_infer_count", infers);
            snapshot.with("detect_model_cache_hit", inferDelta <= 0 ? 0.0
                    : Math.max(0, Math.min(1.0, cacheDelta / (double) inferDelta)));
            prevInfers = infers;
            prevCacheHits = cacheHits;
        } catch (RuntimeException e) {
            // 遥测失败不得影响任何链路
        }
    }

    private static double mean(long sumDelta, long countDelta) {
        if (countDelta <= 0) return 0.0;
        return Math.max(0, sumDelta) / (double) countDelta;
    }
}