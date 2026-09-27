package com.potatotv.paccclient.detection.analysis;

import java.util.List;

/**
 * 点击间隔分布分析（文档 §3.1.1）：人类点击间隔近似对数正态分布，连点器间隔近似固定值/均匀分布。
 *
 * <p>对最近 N 次点击间隔做单样本 KS 检验（拟合对数正态，MLE 估 mu/sigma）并结合变异系数、
 * 偏度、超额峰度打分；间隔样本少于 {@value #MIN_SAMPLES} 时判为样本不足。</p>
 */
public final class ClickIntervalAnalyzer {

    /** 判定所需最少间隔样本数（文档 §3.1.1）。 */
    public static final int MIN_SAMPLES = 30;
    /** 爆发点击间隔阈值（ms）。 */
    public static final double BURST_INTERVAL_MS = 30.0;

    /** 判定结论。 */
    public enum Verdict {
        /** 样本不足，不做判定。 */
        INSUFFICIENT,
        /** 正常人类。 */
        NORMAL,
        /** 疑似连点器。 */
        SUSPECTED,
        /** 高度疑似连点器。 */
        HIGHLY_LIKELY
    }

    /**
     * 点击分布分析结果。
     *
     * @param mean       间隔均值（ms）
     * @param std        间隔标准差（ms）
     * @param cv         变异系数 std/mean
     * @param skewness   偏度
     * @param kurtosis   超额峰度
     * @param ksStat     与拟合对数正态分布的 KS 统计量
     * @param score      0-110 的连点器分值
     * @param verdict    结论
     * @param samples    参与分析的间隔数
     * @param burstCount 间隔 &lt;30ms 的数量
     * @param burstRatio 爆发占比
     */
    public record ClickAnalysis(double mean, double std, double cv, double skewness, double kurtosis,
                                double ksStat, int score, Verdict verdict, int samples,
                                int burstCount, double burstRatio) {

        /** 样本不足时的空结果。 */
        public static ClickAnalysis insufficient() {
            return new ClickAnalysis(0, 0, 0, 0, 0, 0, 0, Verdict.INSUFFICIENT, 0, 0, 0);
        }
    }

    /**
     * 分析点击间隔序列（单位毫秒）。
     *
     * @param intervals 相邻点击的时间差，升序或乱序皆可（内部排序）
     */
    public ClickAnalysis analyze(List<Long> intervals) {
        if (intervals == null || intervals.size() < MIN_SAMPLES) return ClickAnalysis.insufficient();

        double[] raw = new double[intervals.size()];
        int burst = 0;
        for (int i = 0; i < intervals.size(); i++) {
            double v = Math.max(1e-3, intervals.get(i));
            raw[i] = v;
            if (v < BURST_INTERVAL_MS) burst++;
        }
        int n = raw.length;
        double mean = Stats.mean(raw);
        double std = Stats.std(raw);
        double cv = Stats.cv(raw);
        double skew = Stats.skewness(raw);
        double kurt = Stats.kurtosis(raw);

        double[] mle = Stats.logNormalMle(raw);
        double ks = Stats.ksLogNormal(Stats.sorted(raw), mle[0], mle[1]);

        boolean lowCv = cv < 0.15;
        boolean normalSkew = Math.abs(skew) < 0.3;
        boolean normalKurt = Math.abs(kurt) < 0.5;
        boolean poorFit = ks > 0.15;

        int score = 0;
        if (lowCv) score += 40;
        if (normalSkew) score += 20;
        if (normalKurt) score += 20;
        if (poorFit) score += 30;

        Verdict verdict = score >= 70 ? Verdict.HIGHLY_LIKELY
                : score >= 40 ? Verdict.SUSPECTED : Verdict.NORMAL;
        return new ClickAnalysis(mean, std, cv, skew, kurt, ks, score, verdict, n, burst,
                (double) burst / n);
    }
}