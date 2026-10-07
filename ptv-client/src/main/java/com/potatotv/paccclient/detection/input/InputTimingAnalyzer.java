package com.potatotv.paccclient.detection.input;

import com.potatotv.paccclient.detection.analysis.Stats;
import com.potatotv.paccclient.detection.samples.InputEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 输入时序纯分析（文档 §3.4）：点击间隔熵、固定间隔、爆发模式、抖动、键鼠同步。
 *
 * <p>无状态、无副作用，入参就是最近一段 {@link InputEvent} 序列，便于单测。熵用间隔直方图
 * （桶宽 5ms）转概率后交给 {@link Stats#shannonEntropy(double[])}。</p>
 */
public final class InputTimingAnalyzer {

    /** 间隔直方图桶宽（ms）。 */
    private static final int HISTOGRAM_BUCKET_MS = 5;
    /** 熵 / 抖动的最少样本数（间隔数）。 */
    private static final int MIN_SAMPLES = 20;
    /** 固定间隔：最少点击数、标准差上限、均值下限。 */
    private static final int FIXED_MIN_CLICKS = 30;
    private static final double FIXED_MAX_STD_MS = 5.0;
    private static final double FIXED_MIN_MEAN_MS = 10.0;
    /** 爆发模式：快-快-慢。 */
    private static final double BURST_SHORT_MS = 30.0;
    private static final double BURST_LONG_MS = 100.0;
    private static final double BURST_RATE = 0.10;
    /** 键鼠同步：最近点击数与时间窗。 */
    private static final int SYNC_RECENT_CLICKS = 100;
    private static final int SYNC_WINDOW_MS = 5;

    private InputTimingAnalyzer() {
    }

    /**
     * 时序统计结果。
     *
     * @param clickEntropy   点击间隔香农熵（样本不足记 0）
     * @param fixedInterval  是否固定间隔点击
     * @param burstPattern   是否爆发点击模式
     * @param clickJitterMs  点击间隔标准差（ms）；样本不足记 -1 表示无数据
     * @param keyClickSync   最近 100 次点击中 ±5ms 内有按键事件的占比；无按键记 0
     * @param clickCount     参与统计的点击数
     */
    public record TimingStats(double clickEntropy, boolean fixedInterval, boolean burstPattern,
                              double clickJitterMs, double keyClickSync, int clickCount) {
    }

    /**
     * 分析一段输入事件。
     *
     * @param events    输入事件（顺序不限，内部按时间戳升序）
     * @param maxClicks 参与分析的点击数上限（≤0 表示不限）
     */
    public static TimingStats analyze(List<InputEvent> events, int maxClicks) {
        if (events == null || events.isEmpty()) {
            return new TimingStats(0.0, false, false, -1.0, 0.0, 0);
        }
        List<InputEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingLong(InputEvent::timestampMillis));

        List<Long> allClicks = new ArrayList<>();
        List<Long> keyTimes = new ArrayList<>();
        for (InputEvent e : sorted) {
            if (e.kind() == InputEvent.Kind.CLICK) {
                allClicks.add(e.timestampMillis());
            } else if (e.kind() == InputEvent.Kind.KEY) {
                keyTimes.add(e.timestampMillis());
            }
        }

        List<Long> clicks = allClicks;
        if (maxClicks > 0 && clicks.size() > maxClicks) {
            clicks = clicks.subList(clicks.size() - maxClicks, clicks.size());
        }
        int clickCount = clicks.size();

        double[] intervals = new double[Math.max(0, clickCount - 1)];
        for (int i = 0; i < intervals.length; i++) {
            intervals[i] = clicks.get(i + 1) - clicks.get(i);
        }

        double entropy = intervals.length >= MIN_SAMPLES ? entropy(intervals) : 0.0;
        double jitter = intervals.length >= MIN_SAMPLES ? Stats.std(intervals) : -1.0;
        boolean fixed = clickCount >= FIXED_MIN_CLICKS && intervals.length > 0
                && Stats.std(intervals) < FIXED_MAX_STD_MS && Stats.mean(intervals) > FIXED_MIN_MEAN_MS;
        boolean burst = burstRate(intervals) > BURST_RATE;
        double sync = keyClickSync(allClicks, keyTimes);

        return new TimingStats(entropy, fixed, burst, jitter, sync, clickCount);
    }

    /** 间隔直方图 → 概率 → 香农熵。 */
    private static double entropy(double[] intervals) {
        double min = intervals[0];
        double max = intervals[0];
        for (double v : intervals) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        int buckets = (int) ((max - min) / HISTOGRAM_BUCKET_MS) + 1;
        int[] counts = new int[buckets];
        for (double v : intervals) {
            int idx = (int) ((v - min) / HISTOGRAM_BUCKET_MS);
            if (idx >= buckets) idx = buckets - 1;
            counts[idx]++;
        }
        double[] probs = new double[buckets];
        for (int i = 0; i < buckets; i++) {
            probs[i] = (double) counts[i] / intervals.length;
        }
        return Stats.shannonEntropy(probs);
    }

    /** 爆发出现率：两个连续短间隔（<30ms）紧跟在长间隔（>100ms）之后的比例。 */
    private static double burstRate(double[] intervals) {
        if (intervals.length < 3) {
            return 0.0;
        }
        int bursts = 0;
        for (int i = 2; i < intervals.length; i++) {
            if (intervals[i] < BURST_SHORT_MS && intervals[i - 1] < BURST_SHORT_MS
                    && intervals[i - 2] > BURST_LONG_MS) {
                bursts++;
            }
        }
        return (double) bursts / intervals.length;
    }

    /** 最近 100 次点击中，±5ms 内存在按键事件的占比。 */
    private static double keyClickSync(List<Long> clicks, List<Long> keyTimes) {
        if (keyTimes.isEmpty() || clicks.isEmpty()) {
            return 0.0;
        }
        int from = Math.max(0, clicks.size() - SYNC_RECENT_CLICKS);
        int total = 0;
        int matched = 0;
        for (int i = from; i < clicks.size(); i++) {
            total++;
            long t = clicks.get(i);
            for (long k : keyTimes) {
                if (Math.abs(k - t) <= SYNC_WINDOW_MS) {
                    matched++;
                    break;
                }
            }
        }
        return total == 0 ? 0.0 : (double) matched / total;
    }
}