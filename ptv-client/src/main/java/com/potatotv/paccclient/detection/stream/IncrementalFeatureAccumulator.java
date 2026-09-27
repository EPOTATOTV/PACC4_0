package com.potatotv.paccclient.detection.stream;

import com.potatotv.paccclient.detection.FeatureSchema;
import com.potatotv.paccclient.detection.FeatureVector;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DF §4.1.1 增量特征累加器：每条事件只做常数级算术更新（加减乘除各 O(1) 次），<b>绝不重算历史</b>。
 *
 * <p>与既有 {@code FeatureCollector} 的关系：本类<b>复用</b>同一套特征模型——键取自
 * {@link FeatureSchema}（{@code feature_} 前缀的 178 维权威表），输出用既有 {@link FeatureVector}
 * 容器，不新造平行特征表。区别只在计算方式：{@code FeatureCollector} 是「100ms 周期全量重算」，
 * 本类是「事件驱动 + 窗口内运行和增量更新」，处理耗时与历史长度无关。</p>
 *
 * <p>每个特征键维护一条定容窗口与一组在线统计量：窗口内样本数、运行和、运行平方和（用于
 * {@link #mean(String)} / {@link #variance(String)} / {@link #std(String)}）、指数滑动均值与
 * 最近值。键空间有界——只跟踪 {@link FeatureSchema} 中已定义的键，未知键被忽略并计入
 * {@link #ignoredKeys()}，避免开放词表把常驻内存撑爆。</p>
 *
 * <p>数值口径：均值/方差由运行和计算（O(1) 淘汰最旧值），与「取窗口内全部原始值再两遍重算」在
 * 浮点容差内一致（见单测）；{@link #min(String)} / {@link #max(String)} 记录的是自创建以来的极值，
 * 不随窗口淘汰重算，仅作诊断用。</p>
 */
public final class IncrementalFeatureAccumulator {

    /** 跟踪键上限：等于权威维度表长度，超出即忽略。 */
    static final int MAX_KEYS = FeatureSchema.size();

    private final int windowSize;
    private final double ewmaAlpha;
    private final Map<String, Stat> stats = new LinkedHashMap<>();

    private long events;
    private long ignoredKeys;
    private long firstNanos;
    private long lastNanos;

    /**
     * @param windowSize 每个特征键的滑动窗口长度（样本数）
     * @param ewmaAlpha  指数滑动均值系数，取值 (0,1]；默认 0.2
     */
    public IncrementalFeatureAccumulator(int windowSize, double ewmaAlpha) {
        this.windowSize = Math.max(1, windowSize);
        this.ewmaAlpha = Math.max(1e-3, Math.min(1.0, ewmaAlpha));
    }

    /** 观测一条事件：记录到达时间并增量更新其中每个特征键（O(1)/键）。 */
    public void observe(long timestampNanos, Map<String, Double> features) {
        events++;
        if (timestampNanos > 0) {
            if (firstNanos == 0) {
                firstNanos = timestampNanos;
            }
            lastNanos = timestampNanos;
        }
        if (features == null || features.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Double> e : features.entrySet()) {
            Double v = e.getValue();
            if (v != null) {
                update(e.getKey(), v);
            }
        }
    }

    /** 观测一条事件（无时间戳）。 */
    public void observe(Map<String, Double> features) {
        observe(0L, features);
    }

    /** 单键 O(1) 更新：入窗 + 淘汰最旧 + 运行和/平方和修正。 */
    public void update(String key, double value) {
        if (key == null || key.isEmpty() || Double.isNaN(value)) {
            return;
        }
        Stat s = stats.get(key);
        if (s == null) {
            if (stats.size() >= MAX_KEYS || FeatureSchema.indexOf(key) < 0) {
                ignoredKeys++;
                return;
            }
            s = new Stat(windowSize, value);
            stats.put(key, s);
        }
        s.push(value, ewmaAlpha);
    }

    /** 累计观测事件数。 */
    public long events() {
        return events;
    }

    /** 因键空间/词表限制被忽略的观测次数。 */
    public long ignoredKeys() {
        return ignoredKeys;
    }

    /** 已跟踪的键数量（常量级上界的实测值）。 */
    public int trackedKeys() {
        return stats.size();
    }

    /** 观测跨度的平均事件速率（事件/秒）；跨度不足返回 0。 */
    public double ratePerSecond() {
        long span = lastNanos - firstNanos;
        return span <= 0 ? 0.0 : (events - 1) * 1_000_000_000.0 / span;
    }

    /** 窗口内样本数。 */
    public long count(String key) {
        Stat s = stats.get(key);
        return s == null ? 0L : s.count;
    }

    /** 窗口内运行和。 */
    public double sum(String key) {
        Stat s = stats.get(key);
        return s == null ? 0.0 : s.sum;
    }

    /** 窗口内均值；无样本返回 0。 */
    public double mean(String key) {
        Stat s = stats.get(key);
        return s == null || s.count == 0 ? 0.0 : s.sum / s.count;
    }

    /** 窗口内总体方差（除以 n）；样本少于 2 返回 0。 */
    public double variance(String key) {
        Stat s = stats.get(key);
        if (s == null || s.count < 2) {
            return 0.0;
        }
        double mean = s.sum / s.count;
        double var = s.sumSq / s.count - mean * mean;
        return var <= 0 ? 0.0 : var;
    }

    /** 窗口内总体标准差。 */
    public double std(String key) {
        return Math.sqrt(variance(key));
    }

    /** 最近一次观测值；无观测返回 0。 */
    public double last(String key) {
        Stat s = stats.get(key);
        return s == null ? 0.0 : s.last;
    }

    /** 指数滑动均值；无观测返回 0。 */
    public double ewma(String key) {
        Stat s = stats.get(key);
        return s == null ? 0.0 : s.ewma;
    }

    /** 自创建以来的最小值（不随窗口淘汰重算）；无观测返回 0。 */
    public double min(String key) {
        Stat s = stats.get(key);
        return s == null ? 0.0 : s.min;
    }

    /** 自创建以来的最大值（不随窗口淘汰重算）；无观测返回 0。 */
    public double max(String key) {
        Stat s = stats.get(key);
        return s == null ? 0.0 : s.max;
    }

    /**
     * 窗口内均值快照（复用既有 {@link FeatureVector} 容器与 {@code FeatureSchema} 键序）。
     * 仅包含已跟踪的键；未观测维度由 {@link FeatureVector#toArray()} 补 0。
     */
    public FeatureVector snapshot() {
        FeatureVector fv = new FeatureVector();
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            fv.put(e.getKey(), mean(e.getKey()));
        }
        return fv;
    }

    /** 单个特征键的窗口与在线统计量（内存 O(窗口长度)）。 */
    private static final class Stat {
        final double[] window;
        int head;
        int count;
        double sum;
        double sumSq;
        double last;
        double ewma;
        double min;
        double max;

        Stat(int capacity, double value) {
            this.window = new double[capacity];
            this.min = value;
            this.max = value;
            this.ewma = value;
        }

        /** O(1) 入窗：满则淘汰最旧并修正运行和。 */
        void push(double value, double alpha) {
            if (count == window.length) {
                double oldest = window[head];
                sum -= oldest;
                sumSq -= oldest * oldest;
                head = (head + 1) % window.length;
                count--;
            }
            window[(head + count) % window.length] = value;
            sum += value;
            sumSq += value * value;
            count++;
            last = value;
            if (value < min) min = value;
            if (value > max) max = value;
            ewma += alpha * (value - ewma);
        }
    }
}