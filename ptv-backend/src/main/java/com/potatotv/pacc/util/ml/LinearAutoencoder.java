package com.potatotv.pacc.util.ml;

import java.util.Random;

/**
 * v4.6 零日外挂检测：线性自编码器（主成分降维重构误差近似）。
 *
 * <p>以基线样本学习低维主成分子空间，将样本映射后重建。合规样本位于主成分流形之上、
 * 重构误差小；异常样本的投影与回建有较大重构残差 {@code ||x - x_hat||}，用作偏离信号。</p>
 *
 * <p>纯 JDK 实现：对样本协方差矩阵做幂迭代 (power iteration) 求前 k 个主成分
 * （提取后做 deflation 移除该方向），随机初值由固定 seed 驱动，结果可复现。</p>
 */
public final class LinearAutoencoder {

    private final int k;
    private final int dim;
    private final double[][] components; // k × d
    private final double[] mean;         // d

    private LinearAutoencoder(int k, double[][] components, double[] mean, int dim) {
        this.k = k;
        this.components = components;
        this.mean = mean;
        this.dim = dim;
    }

    /**
     * 从基线样本拟合线性自编码器。
     *
     * @param samples n × d 基线样本
     * @param k       主成分数量
     * @param maxIter 幂迭代轮数
     * @param seed    随机种子
     */
    public static LinearAutoencoder fit(double[][] samples, int k, int maxIter, long seed) {
        int n = samples.length;
        int d = samples[0].length;
        int kk = Math.max(1, Math.min(k, d));

        double[] mean = new double[d];
        for (double[] row : samples) for (int j = 0; j < d; j++) mean[j] += row[j];
        for (int j = 0; j < d; j++) mean[j] /= n;

        // 样本协方差矩阵 C[d][d] = (1/n) Σ (x-μ)(x-μ)^T
        double[][] cov = new double[d][d];
        for (double[] row : samples) {
            double[] c = new double[d];
            for (int j = 0; j < d; j++) c[j] = row[j] - mean[j];
            for (int i = 0; i < d; i++)
                for (int j = 0; j < d; j++) cov[i][j] += c[i] * c[j];
        }
        for (int i = 0; i < d; i++) for (int j = 0; j < d; j++) cov[i][j] /= n;

        Random rng = new Random(seed);
        double[][] comps = new double[kk][d];
        double[][] residual = cov;
        for (int c = 0; c < kk; c++) {
            double[] v = randomUnit(d, rng);
            for (int it = 0; it < maxIter; it++) {
                double[] t = new double[d];
                for (int i = 0; i < d; i++) {
                    double s = 0;
                    for (int j = 0; j < d; j++) s += residual[i][j] * v[j];
                    t[i] = s;
                }
                double norm = 0;
                for (double x : t) norm += x * x;
                norm = Math.sqrt(norm);
                if (norm < 1e-12) { v = randomUnit(d, rng); continue; }
                for (int i = 0; i < d; i++) v[i] = t[i] / norm;
            }
            double lambda = 0;
            for (int i = 0; i < d; i++) {
                double s = 0;
                for (int j = 0; j < d; j++) s += residual[i][j] * v[j];
                lambda += v[i] * s;
            }
            comps[c] = v.clone();
            for (int i = 0; i < d; i++) for (int j = 0; j < d; j++) residual[i][j] -= lambda * v[i] * v[j];
        }
        return new LinearAutoencoder(kk, comps, mean, d);
    }

    private static double[] randomUnit(int d, Random rng) {
        double[] v = new double[d];
        double norm = 0;
        while (norm < 1e-12) {
            norm = 0;
            for (int i = 0; i < d; i++) { v[i] = rng.nextGaussian(); norm += v[i] * v[i]; }
            norm = Math.sqrt(norm);
        }
        for (int i = 0; i < d; i++) v[i] /= norm;
        return v;
    }

    /**
     * 重构误差 ||x - x_hat||，越大表示越偏离主成分流形（越异常）。
     */
    public double reconstructionError(double[] x) {
        double[] centered = new double[dim];
        for (int i = 0; i < dim; i++) centered[i] = x[i] - mean[i];
        double[] coeff = new double[k];
        for (int c = 0; c < k; c++) {
            double s = 0;
            for (int i = 0; i < dim; i++) s += components[c][i] * centered[i];
            coeff[c] = s;
        }
        double[] xHat = new double[dim];
        for (int i = 0; i < dim; i++) xHat[i] = mean[i];
        for (int c = 0; c < k; c++) for (int i = 0; i < dim; i++) xHat[i] += coeff[c] * components[c][i];
        double err = 0;
        for (int i = 0; i < dim; i++) { double g = x[i] - xHat[i]; err += g * g; }
        return Math.sqrt(err);
    }

    /** 主成分数量（供测试）。 */
    public int componentCount() {
        return k;
    }
}