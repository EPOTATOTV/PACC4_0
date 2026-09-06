package com.potatotv.pacc.util.ml;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * v4.6 威胁情报：纯 Java 指纹向量 + K-Means 家族聚类。
 *
 * <p>将静态维度键值切分为 token，经哈希桶映射为固定维度词袋向量；再以 K-Means 将样本聚为
 * 若干语义家族，识别"同一作者/同一外挂"的软件簇。随机初始化由固定 seed 驱动，结果可复现。</p>
 */
public final class FingerprintClusterer {

    private FingerprintClusterer() {
    }

    /** 将一组 token 映射为 dims 维词袋向量（确定性哈希桶累加）。 */
    public static double[] vectorOf(Collection<String> tokens, int dims) {
        double[] v = new double[dims];
        if (tokens == null) return v;
        for (String t : tokens) {
            if (t == null || t.isBlank()) continue;
            int bucket = (t.hashCode() & 0x7fffffff) % dims;
            v[bucket] += 1;
        }
        return v;
    }

    /** 将键值对文本切分为可判别的 token（类名/方法名/路径/指标等）。 */
    public static List<String> tokenize(Map<String, String> dims) {
        List<String> out = new ArrayList<>();
        if (dims == null) return out;
        for (Map.Entry<String, String> e : dims.entrySet()) {
            StringBuilder sb = new StringBuilder();
            if (e.getKey() != null) sb.append(e.getKey()).append(' ');
            if (e.getValue() != null) sb.append(e.getValue());
            for (String tok : sb.toString().split("[^A-Za-z0-9_]+")) {
                if (tok != null && !tok.isBlank() && tok.length() >= 2) out.add(tok.toLowerCase());
            }
        }
        return out;
    }

    /**
     * K-Means 聚类。
     *
     * @param rows    样本向量（n × dims，dim 必须一致）
     * @param k       家族数量
     * @param maxIter 最大迭代
     * @param seed    随机种子（初始中心选择可复现）
     * @return 每个样本的簇标签 [0, k)
     */
    public static int[] kmeans(List<double[]> rows, int k, int maxIter, long seed) {
        int n = rows.size();
        if (n == 0) return new int[0];
        int dims = rows.get(0).length;
        int kk = Math.max(1, Math.min(k, n));
        Random rng = new Random(seed);

        // 随机选 kk 个样本作初始中心
        double[][] centers = new double[kk][dims];
        boolean[] used = new boolean[n];
        int got = 0;
        while (got < kk) {
            int idx = rng.nextInt(n);
            if (used[idx]) continue;
            used[idx] = true;
            for (int j = 0; j < dims; j++) centers[got][j] = rows.get(idx)[j];
            got++;
        }

        int[] label = new int[n];
        for (int iter = 0; iter < maxIter; iter++) {
            // 分配
            for (int i = 0; i < n; i++) {
                double best = Double.MAX_VALUE;
                int bestC = 0;
                for (int c = 0; c < kk; c++) {
                    double d = squared(rows.get(i), centers[c]);
                    if (d < best) { best = d; bestC = c; }
                }
                label[i] = bestC;
            }
            // 更新中心；若某簇空，保留原中心
            double[][] sum = new double[kk][dims];
            int[] count = new int[kk];
            for (int i = 0; i < n; i++) {
                int c = label[i];
                for (int j = 0; j < dims; j++) sum[c][j] += rows.get(i)[j];
                count[c]++;
            }
            boolean moved = false;
            for (int c = 0; c < kk; c++) {
                if (count[c] == 0) continue;
                double[] next = new double[dims];
                for (int j = 0; j < dims; j++) next[j] = sum[c][j] / count[c];
                if (squared(next, centers[c]) > 1e-12) moved = true;
                centers[c] = next;
            }
            if (!moved) break;
            // 重新分配上次标签作下次参考不影响收敛
        }
        return label;
    }

    /** 各簇样本数统计。 */
    public static Map<Integer, Integer> clusterSizes(int[] label) {
        Map<Integer, Integer> m = new HashMap<>();
        for (int l : label) m.merge(l, 1, Integer::sum);
        return m;
    }

    private static double squared(double[] a, double[] b) {
        double s = 0;
        for (int j = 0; j < a.length; j++) { double d = a[j] - b[j]; s += d * d; }
        return s;
    }
}