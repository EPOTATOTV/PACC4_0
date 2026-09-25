package com.potatotv.paccclient.detection.analysis;

import java.util.Arrays;

/**
 * 端侧统计工具（纯 JDK，无第三方依赖）：矩统计、正态/对数正态分布与 Kolmogorov–Smirnov 统计量。
 * <p>供点击间隔分布检验、轨迹与时序建模复用。</p>
 */
public final class Stats {

    private Stats() {
    }

    /** 算术平均；空数组返回 0。 */
    public static double mean(double[] a) {
        if (a == null || a.length == 0) return 0.0;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    /** 总体方差（除以 n）；少于 2 个样本返回 0。 */
    public static double variance(double[] a) {
        if (a == null || a.length < 2) return 0.0;
        double m = mean(a);
        double s = 0;
        for (double v : a) s += (v - m) * (v - m);
        return s / a.length;
    }

    /** 总体标准差。 */
    public static double std(double[] a) {
        return Math.sqrt(variance(a));
    }

    /** 变异系数 std/mean；均值趋于 0 时返回 0。 */
    public static double cv(double[] a) {
        double m = mean(a);
        if (Math.abs(m) < 1e-12) return 0.0;
        return std(a) / Math.abs(m);
    }

    /** 偏度（三阶标准化中心矩）；标准差趋于 0 时返回 0。 */
    public static double skewness(double[] a) {
        double s = std(a);
        if (s < 1e-12 || a.length < 2) return 0.0;
        double m = mean(a);
        double acc = 0;
        for (double v : a) acc += Math.pow((v - m) / s, 3);
        return acc / a.length;
    }

    /** 峰度（四阶标准化中心矩减 3，即超额峰度）；标准差趋于 0 时返回 0。 */
    public static double kurtosis(double[] a) {
        double s = std(a);
        if (s < 1e-12 || a.length < 2) return 0.0;
        double m = mean(a);
        double acc = 0;
        for (double v : a) acc += Math.pow((v - m) / s, 4);
        return acc / a.length - 3.0;
    }

    /** p 分位（0~1），输入需已升序；空数组返回 0。 */
    public static double percentile(double[] sorted, double p) {
        if (sorted == null || sorted.length == 0) return 0.0;
        double pos = Math.max(0, Math.min(1, p)) * (sorted.length - 1);
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        double w = pos - lo;
        return sorted[lo] * (1 - w) + sorted[hi] * w;
    }

    /** 升序副本。 */
    public static double[] sorted(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c;
    }

    /**
     * 误差函数 erf(x)，Abramowitz & Stegun 7.1.26 近似（绝对误差 &lt; 1.5e-7），
     * 用于对数正态 CDF 计算。
     */
    public static double erf(double x) {
        double sign = Math.signum(x);
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.3275911 * z);
        double y = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
                + 0.254829592) * t * Math.exp(-z * z);
        return sign * y;
    }

    /** 标准正态 CDF。 */
    public static double normalCdf(double z) {
        return 0.5 * (1.0 + erf(z / Math.sqrt(2.0)));
    }

    /** 对数正态分布 CDF：x&gt;0 时 P = Φ((ln x - mu)/sigma)；x≤0 时为 0。 */
    public static double logNormalCdf(double x, double mu, double sigma) {
        if (x <= 0) return 0.0;
        if (sigma < 1e-12) return x >= Math.exp(mu) ? 1.0 : 0.0;
        return normalCdf((Math.log(x) - mu) / sigma);
    }

    /**
     * 单样本 Kolmogorov–Smirnov 统计量 D（对比拟合的对数正态分布）。
     *
     * @param sortedPositive 升序、正值样本
     * @return D = max(上偏, 下偏)，样本为空返回 0
     */
    public static double ksLogNormal(double[] sortedPositive, double mu, double sigma) {
        int n = sortedPositive.length;
        if (n == 0) return 0.0;
        double d = 0;
        for (int i = 0; i < n; i++) {
            double f = logNormalCdf(sortedPositive[i], mu, sigma);
            double upper = (i + 1.0) / n - f;
            double lower = f - (double) i / n;
            d = Math.max(d, Math.max(upper, lower));
        }
        return d;
    }

    /**
     * 对数正态 MLE：mu = mean(ln x)，sigma = 总体标准差(ln x)。
     *
     * @param positive 全部为正的样本
     * @return {mu, sigma}；样本为空返回 {0, 1}
     */
    public static double[] logNormalMle(double[] positive) {
        int n = positive.length;
        if (n == 0) return new double[]{0.0, 1.0};
        double[] logs = new double[n];
        for (int i = 0; i < n; i++) logs[i] = Math.log(Math.max(1e-9, positive[i]));
        double mu = mean(logs);
        double sigma = n < 2 ? 1.0 : std(logs);
        if (sigma < 1e-9) sigma = 1e-9;
        return new double[]{mu, sigma};
    }

    /** 香农熵（bit）：对已归一化的概率数组求和 -p·log2(p)。 */
    public static double shannonEntropy(double[] probabilities) {
        double h = 0;
        for (double p : probabilities) {
            if (p > 1e-12) h -= p * (Math.log(p) / Math.log(2.0));
        }
        return h;
    }

    /** 余弦相似度；任一向量为零向量时返回 0。 */
    public static double cosine(double ax, double ay, double bx, double by) {
        double na = Math.hypot(ax, ay);
        double nb = Math.hypot(bx, by);
        if (na < 1e-12 || nb < 1e-12) return 0.0;
        return (ax * bx + ay * by) / (na * nb);
    }
}