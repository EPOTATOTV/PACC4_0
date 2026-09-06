package com.potatotv.pacc.util.ml;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * v4.6 零日外挂检测：纯 Java 孤立森林 (Isolation Forest)。
 *
 * <p>用于在无先验外挂特征时识别"分布外"的异常行为：合规样本构建森林，异常样本路径更短、
 * 更易被隔离，独离分数 s(x) ∈ (0,1] 趋高表示越可疑。模型在特征空间中不依赖具体外挂签名，
 * 因此对未知外挂（零日）具备泛化能力。</p>
 *
 * <p>纯 JDK 实现（无第三方 ML 依赖），随机过程由固定 seed 驱动，保证可复现与可单测。</p>
 */
public final class IsolationForest {

    private static final double EULER_GAMMA = 0.5772156649015328606;

    private final Tree[] trees;
    private final int subSize;

    /** 每一棵树的随机子采样。 */
    private static final class Tree {
        int feature;
        double split;
        Tree left;
        Tree right;
        int size;
    }

    /**
     * @param samples 基线样本矩（n × d），d 固定
     * @param nTrees  树的数量
     * @param subSize 每棵树的子采样大小
     * @param maxDepth 树的最大深度
     * @param seed    随机种子（可复现）
     */
    public IsolationForest(double[][] samples, int nTrees, int subSize, int maxDepth, long seed) {
        this.subSize = Math.min(subSize, samples.length);
        Random rng = new Random(seed);
        this.trees = new Tree[nTrees];
        for (int i = 0; i < nTrees; i++) {
            double[][] sub = subsample(samples, this.subSize, rng);
            this.trees[i] = build(sub, 0, maxDepth, rng);
        }
    }

    private static double[][] subsample(double[][] src, int size, Random rng) {
        int n = src.length;
        if (size >= n) return src.clone();
        // 无放回抽样
        double[][] out = new double[size][];
        boolean[] used = new boolean[n];
        int got = 0;
        while (got < size) {
            int idx = rng.nextInt(n);
            if (used[idx]) continue;
            used[idx] = true;
            out[got++] = src[idx];
        }
        return out;
    }

    private Tree build(double[][] sub, int depth, int maxDepth, Random rng) {
        int n = sub.length;
        int d = sub[0].length;
        Tree t = new Tree();
        t.size = n;
        if (depth >= maxDepth || n <= 1) return t;

        int f = rng.nextInt(d);
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double[] row : sub) {
            double v = row[f];
            if (v < mn) mn = v;
            if (v > mx) mx = v;
        }
        if (mx <= mn) return t; // 该特征在本子集恒为常数，无法分裂

        double split = mn + rng.nextDouble() * (mx - mn);
        List<double[]> left = new ArrayList<>(), right = new ArrayList<>();
        for (double[] row : sub) {
            if (row[f] < split) left.add(row);
            else right.add(row);
        }
        if (left.isEmpty() || right.isEmpty()) return t;

        t.feature = f;
        t.split = split;
        t.left = build(left.toArray(new double[0][]), depth + 1, maxDepth, rng);
        t.right = build(right.toArray(new double[0][]), depth + 1, maxDepth, rng);
        return t;
    }

    private static double avgDepthOfSize(int size) {
        if (size <= 1) return 0;
        if (size == 2) return 1;
        return 2 * (Math.log(size - 1) + EULER_GAMMA) - 2.0 * (size - 1) / size;
    }

    private double depthOf(Tree t, double[] x, int depth) {
        if (t.left == null) return depth + avgDepthOfSize(t.size);
        return (x[t.feature] < t.split) ? depthOf(t.left, x, depth + 1) : depthOf(t.right, x, depth + 1);
    }

    /** 归一化常量 c(subSize)，用于将平均路径长度映射为 (0,1] 独离分数。 */
    private double cNorm() {
        if (subSize <= 1) return 1;
        return avgDepthOfSize(subSize);
    }

    /**
     * 独离分数 s(x) ∈ (0,1]，越接近 1 表示样本越孤立/越可能是异常。
     */
    public double anomalyScore(double[] x) {
        double sum = 0;
        for (Tree tree : trees) sum += depthOf(tree, x, 0);
        double avgDepth = sum / trees.length;
        double c = cNorm();
        if (c <= 0 || avgDepth <= 0) return 0.0;
        return Math.pow(2, -avgDepth / c);
    }
}