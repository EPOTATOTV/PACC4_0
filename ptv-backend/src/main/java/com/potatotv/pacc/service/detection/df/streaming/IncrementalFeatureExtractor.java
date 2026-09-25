package com.potatotv.pacc.service.detection.df.streaming;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DF §4.1.1 增量特征提取：每次事件只做常数级算术更新（加减乘除各 O(1) 次），<b>绝不重算历史</b>。
 *
 * <p>每个特征键维护一组在线统计量（Welford 风格的均值/总体标准差、指数滑动均值 EWMA、极值），
 * 单次 {@link #update(String, double)} 的运算次数与已观测样本数无关，因此整条流水线的特征计算
 * 复杂度为 O(1)/事件、O(N)/批——这正是「100ms 周期全量重算」被替换掉的根因。</p>
 *
 * <p>键空间有界（{@value #MAX_KEYS}）：超出后新键被忽略并计入 {@link #ignoredKeys()}，避免开放词表
 * 把常驻内存撑爆；这是有意的取舍，而非静默丢弃特征值。</p>
 */
public final class IncrementalFeatureExtractor {

    /** 跟踪键上限（有界内存）。 */
    static final int MAX_KEYS = 256;

    private final double ewmaAlpha;
    private final Map<String, Stat> stats = new LinkedHashMap<>();
    private long updates;
    private long ignoredKeys;

    /** @param ewmaAlpha 指数滑动均值系数，取值 (0,1]；默认 0.2 */
    public IncrementalFeatureExtractor(double ewmaAlpha) {
        this.ewmaAlpha = Math.max(1e-3, Math.min(1.0, ewmaAlpha));
    }

    /** 观测一个特征值：O(1) 更新（常数次算术运算）。 */
    public void update(String key, double value) {
        if (key == null || key.isEmpty() || Double.isNaN(value)) {
            return;
        }
        Stat s = stats.get(key);
        if (s == null) {
            if (stats.size() >= MAX_KEYS) {
                ignoredKeys++;
                return;
            }
            s = new Stat();
            s.min = value;
            s.max = value;
            s.ewma = value;
            stats.put(key, s);
        }
        s.n++;
        s.sum += value;
        s.sumSq += value * value;
        s.last = value;
        if (value < s.min) s.min = value;
        if (value > s.max) s.max = value;
        s.ewma += ewmaAlpha * (value - s.ewma);
    }

    /** 观测一组特征值。 */
    public void updateAll(Map<String, Double> values) {
        if (values == null) {
            return;
        }
        for (Map.Entry<String, Double> e : values.entrySet()) {
            if (e.getValue() != null) {
                update(e.getKey(), e.getValue());
            }
        }
    }

    /** 累计事件观测次数。 */
    public long updates() {
        return updates;
    }

    /** 记一次事件观测（每次 ingest 调用一次，用于事件计数）。 */
    public void countEvent() {
        updates++;
    }

    /** 已跟踪的键数量（常量级上界的实测值）。 */
    public int trackedKeys() {
        return stats.size();
    }

    /** 因键空间已满被忽略的键次数。 */
    public long ignoredKeys() {
        return ignoredKeys;
    }

    /** 在线均值；无观测返回 0。 */
    public double mean(String key) {
        Stat s = stats.get(key);
        return s == null || s.n == 0 ? 0.0 : s.sum / s.n;
    }

    /** 总体标准差（除以 n）；无观测返回 0。 */
    public double std(String key) {
        Stat s = stats.get(key);
        if (s == null || s.n == 0) {
            return 0.0;
        }
        double mean = s.sum / s.n;
        double var = s.sumSq / s.n - mean * mean;
        return var <= 0 ? 0.0 : Math.sqrt(var);
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

    /** 观测最小值；无观测返回 0。 */
    public double min(String key) {
        Stat s = stats.get(key);
        return s == null ? 0.0 : s.min;
    }

    /** 观测最大值；无观测返回 0。 */
    public double max(String key) {
        Stat s = stats.get(key);
        return s == null ? 0.0 : s.max;
    }

    /** 某键的观测样本数。 */
    public long samples(String key) {
        Stat s = stats.get(key);
        return s == null ? 0L : s.n;
    }

    /** 派生特征快照（供 AI 精判层消费）：均值 / 总体标准差 / 极差 / EWMA。 */
    public Map<String, Double> snapshot() {
        Map<String, Double> out = new LinkedHashMap<>();
        out.put("event_count", (double) updates);
        for (Map.Entry<String, Stat> e : stats.entrySet()) {
            String key = e.getKey();
            Stat s = e.getValue();
            out.put(key + "_mean", mean(key));
            out.put(key + "_std", std(key));
            out.put(key + "_range", s.max - s.min);
            out.put(key + "_ewma", s.ewma);
        }
        return out;
    }

    /** 单个特征键的在线统计量（固定字段，内存 O(1)）。 */
    private static final class Stat {
        long n;
        double sum;
        double sumSq;
        double last;
        double min;
        double max;
        double ewma;
    }
}