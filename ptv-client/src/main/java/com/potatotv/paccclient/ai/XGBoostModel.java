package com.potatotv.paccclient.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 纯 Java 梯度提升树（GBDT）模型：支持逻辑损失训练与推理，零第三方 ML 依赖（文档 §2.1.2）。
 *
 * <p>结构约束：最多 {@link #MAX_TREES} 棵树、每棵最多 {@link #MAX_DEPTH} 层、float 分裂阈值。
 * 推理为 {@code sigmoid(baseScore + Σ 叶子值)}，可解释性地输出每个特征的有符号贡献度
 * （文档 §1.3「每个 AI 判定必须输出特征贡献度」）。</p>
 *
 * <p>权重自描述二进制布局（大端序）：</p>
 * <pre>
 * [4B] magic "XGB1"
 * [4B] featureDim
 * [4B] maxDepth
 * [8B] baseScore (double)
 * [4B] treeCount
 * 每棵树: [4B] nodeCount，随后 nodeCount × 20B 节点
 *         节点 = [4B] featureIndex（-1 表示叶子）
 *                [4B] threshold (float)
 *                [4B] left（子节点在全局扁平数组中的下标，叶子为 -1）
 *                [4B] right
 *                [4B] leaf (float)
 * </pre>
 */
public final class XGBoostModel {

    /** 叶子节点标记：featureIndex == LEAF。 */
    public static final int LEAF = -1;
    public static final int MAX_TREES = 200;
    public static final int MAX_DEPTH = 8;

    private static final byte[] MAGIC = {'X', 'G', 'B', '1'};
    private static final int NODE_BYTES = 20;
    private static final int QUANTILES = 16;
    private static final int MAX_NODES_PER_TREE = 4095;
    private static final double LAMBDA = 1.0;
    private static final double MIN_CHILD_WEIGHT = 1.0;

    private final int featureDim;
    private final int maxDepth;
    private final double baseScore;
    private final int[] treeStart;      // 长度 = 树数 + 1
    private final int[] featureIndex;   // 全局扁平节点数组
    private final float[] threshold;
    private final int[] left;
    private final int[] right;
    private final float[] leaf;
    private final double[] nodeValue;   // 内部节点子树期望值（贡献度分解用，反序列化后重建）

    private XGBoostModel(int featureDim, int maxDepth, double baseScore, int[] treeStart,
                         int[] featureIndex, float[] threshold, int[] left, int[] right, float[] leaf) {
        this.featureDim = featureDim;
        this.maxDepth = maxDepth;
        this.baseScore = baseScore;
        this.treeStart = treeStart;
        this.featureIndex = featureIndex;
        this.threshold = threshold;
        this.left = left;
        this.right = right;
        this.leaf = leaf;
        this.nodeValue = new double[featureIndex.length];
        for (int t = 0; t < treeCount(); t++) fillNodeValue(treeStart[t]);
    }

    /** 后序遍历重建子树期望值：叶子取叶值，内部节点取子树加权均值。返回子树叶子数。 */
    private int fillNodeValue(int idx) {
        if (featureIndex[idx] == LEAF) {
            nodeValue[idx] = leaf[idx];
            return 1;
        }
        int lc = fillNodeValue(left[idx]);
        int rc = fillNodeValue(right[idx]);
        nodeValue[idx] = (nodeValue[left[idx]] * lc + nodeValue[right[idx]] * rc) / (lc + rc);
        return lc + rc;
    }

    /** 原始 margin：baseScore + 各树叶子值之和。 */
    public double margin(double[] x) {
        double s = baseScore;
        for (int t = 0; t < treeCount(); t++) {
            int n = treeStart[t];
            while (featureIndex[n] != LEAF) {
                int f = featureIndex[n];
                double v = f < x.length ? x[f] : 0.0;
                n = v <= threshold[n] ? left[n] : right[n];
            }
            s += leaf[n];
        }
        return s;
    }

    /** 预测概率：sigmoid(margin)。 */
    public double predict(double[] x) {
        return sigmoid(margin(x));
    }

    /**
     * 每维特征的有符号贡献度：样本沿每棵树下降时，内部节点贡献 = 子节点期望值 - 当前节点期望值，
     * 累加到该节点所用特征上。由望远镜求和可知 Σ 贡献 = margin - baseScore，支持人工复核。
     */
    public double[] featureContributions(double[] x) {
        double[] c = new double[featureDim];
        for (int t = 0; t < treeCount(); t++) {
            int n = treeStart[t];
            while (featureIndex[n] != LEAF) {
                int f = featureIndex[n];
                double v = f < x.length ? x[f] : 0.0;
                int child = v <= threshold[n] ? left[n] : right[n];
                if (f >= 0 && f < featureDim) c[f] += nodeValue[child] - nodeValue[n];
                n = child;
            }
        }
        return c;
    }

    /** 按绝对值降序输出特征贡献度（键为特征名，未提供名字时回退 "f{下标}"）。 */
    public Map<String, Double> contributions(double[] x, List<String> featureNames) {
        double[] c = featureContributions(x);
        List<Integer> order = new ArrayList<>(c.length);
        for (int i = 0; i < c.length; i++) order.add(i);
        order.sort((a, b) -> Double.compare(Math.abs(c[b]), Math.abs(c[a])));
        Map<String, Double> out = new LinkedHashMap<>();
        for (int i : order) {
            String name = (featureNames != null && i < featureNames.size()) ? featureNames.get(i) : "f" + i;
            out.put(name, c[i]);
        }
        return out;
    }

    /** 序列化为自描述字节（见类注释布局）。 */
    public byte[] serialize() {
        int trees = treeCount();
        int total = featureIndex.length;
        int cap = 4 + 4 + 4 + 8 + 4 + total * NODE_BYTES + trees * 4;
        ByteBuffer bb = ByteBuffer.allocate(cap).order(ByteOrder.BIG_ENDIAN);
        bb.put(MAGIC);
        bb.putInt(featureDim);
        bb.putInt(maxDepth);
        bb.putDouble(baseScore);
        bb.putInt(trees);
        for (int t = 0; t < trees; t++) {
            int start = treeStart[t];
            int count = treeStart[t + 1] - start;
            bb.putInt(count);
            for (int k = 0; k < count; k++) {
                int i = start + k;
                bb.putInt(featureIndex[i]);
                bb.putFloat(threshold[i]);
                bb.putInt(left[i]);
                bb.putInt(right[i]);
                bb.putFloat(leaf[i]);
            }
        }
        return bb.array();
    }

    /** 反序列化；{@code featureDim} 为调用方声明的特征维度，须与载荷一致。 */
    public static XGBoostModel deserialize(byte[] weights, int featureDim) {
        if (weights == null || weights.length < 28) throw new IllegalArgumentException("XGBoost 权重数据过短");
        for (int i = 0; i < 4; i++) {
            if (weights[i] != MAGIC[i]) throw new IllegalArgumentException("XGBoost 权重魔数非法");
        }
        ByteBuffer bb = ByteBuffer.wrap(weights).order(ByteOrder.BIG_ENDIAN);
        bb.position(4);
        int fd = bb.getInt();
        if (fd != featureDim) throw new IllegalArgumentException("特征维度不匹配: " + fd + " != " + featureDim);
        int md = bb.getInt();
        double base = bb.getDouble();
        int trees = bb.getInt();
        if (trees < 0 || trees > MAX_TREES) throw new IllegalArgumentException("树数量非法: " + trees);

        int cursor = bb.position();
        int[] counts = new int[trees];
        int total = 0;
        for (int t = 0; t < trees; t++) {
            if (cursor + 4 > weights.length) throw new IllegalArgumentException("权重数据被截断");
            int nc = bb.getInt(cursor);
            if (nc <= 0 || nc > MAX_NODES_PER_TREE) throw new IllegalArgumentException("单树节点数非法: " + nc);
            if (cursor + 4L + (long) nc * NODE_BYTES > weights.length) throw new IllegalArgumentException("权重数据被截断");
            counts[t] = nc;
            total += nc;
            cursor += 4 + nc * NODE_BYTES;
        }
        if (cursor != weights.length) throw new IllegalArgumentException("权重数据长度不一致");

        int[] start = new int[trees + 1];
        int[] fi = new int[total];
        float[] thr = new float[total];
        int[] lf = new int[total];
        int[] rt = new int[total];
        float[] lv = new float[total];
        int p = 0;
        int c = bb.position();
        for (int t = 0; t < trees; t++) {
            start[t] = p;
            int nc = counts[t];
            c += 4;
            for (int k = 0; k < nc; k++) {
                int fidx = bb.getInt(c);
                thr[p] = bb.getFloat(c + 4);
                lf[p] = bb.getInt(c + 8);
                rt[p] = bb.getInt(c + 12);
                lv[p] = bb.getFloat(c + 16);
                c += NODE_BYTES;
                if (fidx != LEAF && (fidx < 0 || fidx >= fd)) throw new IllegalArgumentException("节点特征下标非法: " + fidx);
                if (lf[p] != -1 && (lf[p] < 0 || lf[p] >= total)) throw new IllegalArgumentException("左子节点下标越界");
                if (rt[p] != -1 && (rt[p] < 0 || rt[p] >= total)) throw new IllegalArgumentException("右子节点下标越界");
                fi[p] = fidx;
                p++;
            }
        }
        start[trees] = p;
        return new XGBoostModel(fd, md, base, start, fi, thr, lf, rt, lv);
    }

    /**
     * 逻辑损失 GBDT 训练（二阶牛顿提升）：对梯度 {@code g=p-y} / 二阶导 {@code h=p(1-p)} 拟合回归树，
     * 叶子取 {@code -G/(H+λ)}；分裂阈值取自每个特征的 {@link #QUANTILES} 分位点，带最小子节点权重护栏，
     * 并按 seed 做特征子采样。相同 seed 下结果完全可复现。
     *
     * @param x            n × d 训练样本
     * @param y            标签（0/1）
     * @param nTrees       树数量（上限 {@link #MAX_TREES}）
     * @param maxDepth     每棵树最大深度（上限 {@link #MAX_DEPTH}）
     * @param learningRate 学习率
     * @param seed         随机种子（特征子采样 + 确定性）
     */
    public static XGBoostModel train(double[][] x, double[] y, int nTrees, int maxDepth,
                                     double learningRate, long seed) {
        if (x == null || y == null || x.length == 0 || x[0] == null || x[0].length == 0 || x.length != y.length) {
            throw new IllegalArgumentException("训练数据非法");
        }
        int n = x.length;
        int d = x[0].length;
        int trees = Math.max(1, Math.min(MAX_TREES, nTrees));
        int depth = Math.max(1, Math.min(MAX_DEPTH, maxDepth));
        double lr = learningRate <= 0 ? 0.3 : learningRate;

        double mean = 0;
        for (double v : y) mean += v;
        mean = Math.max(1e-3, Math.min(1 - 1e-3, mean / n));
        double base = Math.max(-4, Math.min(4, Math.log(mean / (1 - mean))));

        double[] pred = new double[n];
        Arrays.fill(pred, base);
        int[] all = new int[n];
        for (int i = 0; i < n; i++) all[i] = i;
        Random rng = new Random(seed);
        List<TreeBuilder> built = new ArrayList<>(trees);

        for (int t = 0; t < trees; t++) {
            double[] g = new double[n];
            double[] h = new double[n];
            for (int i = 0; i < n; i++) {
                double p = sigmoid(pred[i]);
                g[i] = p - y[i];
                h[i] = p * (1 - p);
            }
            TreeBuilder tb = new TreeBuilder();
            buildNode(tb, x, g, h, all, 0, depth, rng, d);
            for (int i = 0; i < n; i++) pred[i] += lr * predictTree(tb, x[i]);
            built.add(tb);
        }

        int total = 0;
        for (TreeBuilder tb : built) total += tb.nodeCount();
        int[] start = new int[trees + 1];
        int[] fi = new int[total];
        float[] thr = new float[total];
        int[] lf = new int[total];
        int[] rt = new int[total];
        float[] lv = new float[total];
        int p = 0;
        for (int t = 0; t < trees; t++) {
            start[t] = p;
            TreeBuilder tb = built.get(t);
            for (int k = 0; k < tb.nodeCount(); k++) {
                fi[p] = tb.feat[k];
                thr[p] = tb.thr[k];
                // 子树内局部下标 → 全局扁平下标
                lf[p] = tb.left[k] < 0 ? LEAF : tb.left[k] + start[t];
                rt[p] = tb.right[k] < 0 ? LEAF : tb.right[k] + start[t];
                lv[p] = tb.leaf[k];
                p++;
            }
        }
        start[trees] = p;
        return new XGBoostModel(d, depth, base, start, fi, thr, lf, rt, lv);
    }

    private static int buildNode(TreeBuilder tb, double[][] x, double[] g, double[] h, int[] idx,
                                 int depth, int maxDepth, Random rng, int featureDim) {
        double G = 0, H = 0;
        for (int i : idx) {
            G += g[i];
            H += h[i];
        }
        int node = tb.add();
        if (depth >= maxDepth || idx.length < 4 || H < 2 * MIN_CHILD_WEIGHT) {
            tb.setLeaf(node, (float) (-G / (H + LAMBDA)));
            return node;
        }

        int mtry = Math.max(1, Math.min(featureDim, (int) Math.ceil(Math.sqrt(featureDim))));
        int[] feats = sampleFeatures(featureDim, mtry, rng);
        double parent = G * G / (H + LAMBDA);
        double bestGain = 0;
        int bestFeat = -1;
        float bestThr = 0;

        for (int f : feats) {
            double[] vals = new double[idx.length];
            for (int j = 0; j < idx.length; j++) vals[j] = x[idx[j]][f];
            Arrays.sort(vals);
            int lastPos = -1;
            for (int q = 1; q <= QUANTILES; q++) {
                int pos = (int) Math.floor((double) q / (QUANTILES + 1) * vals.length);
                if (pos <= 0) continue;
                if (pos >= vals.length) pos = vals.length - 1;
                if (pos == lastPos) continue;
                lastPos = pos;
                if (vals[pos] == vals[pos - 1]) continue;
                float thr = (float) vals[pos];
                double gl = 0, hl = 0, gr = 0, hr = 0;
                for (int i : idx) {
                    if (x[i][f] <= thr) {
                        gl += g[i];
                        hl += h[i];
                    } else {
                        gr += g[i];
                        hr += h[i];
                    }
                }
                if (hl < MIN_CHILD_WEIGHT || hr < MIN_CHILD_WEIGHT) continue;
                double gain = 0.5 * (gl * gl / (hl + LAMBDA) + gr * gr / (hr + LAMBDA) - parent);
                if (gain > bestGain) {
                    bestGain = gain;
                    bestFeat = f;
                    bestThr = thr;
                }
            }
        }

        if (bestFeat < 0) {
            tb.setLeaf(node, (float) (-G / (H + LAMBDA)));
            return node;
        }
        tb.setSplit(node, bestFeat, bestThr);
        int nl = 0;
        for (int i : idx) if (x[i][bestFeat] <= bestThr) nl++;
        int[] leftIdx = new int[nl];
        int[] rightIdx = new int[idx.length - nl];
        int a = 0, b = 0;
        for (int i : idx) {
            if (x[i][bestFeat] <= bestThr) leftIdx[a++] = i;
            else rightIdx[b++] = i;
        }
        tb.setLeft(node, buildNode(tb, x, g, h, leftIdx, depth + 1, maxDepth, rng, featureDim));
        tb.setRight(node, buildNode(tb, x, g, h, rightIdx, depth + 1, maxDepth, rng, featureDim));
        return node;
    }

    private static double predictTree(TreeBuilder tb, double[] x) {
        int n = 0;
        while (tb.feat[n] != LEAF) {
            int f = tb.feat[n];
            n = (f < x.length ? x[f] : 0.0) <= tb.thr[n] ? tb.left[n] : tb.right[n];
        }
        return tb.leaf[n];
    }

    private static int[] sampleFeatures(int d, int mtry, Random rng) {
        int[] all = new int[d];
        for (int i = 0; i < d; i++) all[i] = i;
        for (int i = 0; i < mtry; i++) {
            int j = i + rng.nextInt(d - i);
            int tmp = all[i];
            all[i] = all[j];
            all[j] = tmp;
        }
        return Arrays.copyOf(all, mtry);
    }

    private static double sigmoid(double z) {
        double c = Math.max(-60, Math.min(60, z));
        return 1.0 / (1.0 + Math.exp(-c));
    }

    public int treeCount() {
        return treeStart.length - 1;
    }

    public int maxDepth() {
        return maxDepth;
    }

    public int featureDim() {
        return featureDim;
    }

    /** 训练期可变长树节点缓冲，训练结束后拍平成全局数组。 */
    private static final class TreeBuilder {
        int[] feat = new int[8];
        float[] thr = new float[8];
        int[] left = new int[8];
        int[] right = new int[8];
        float[] leaf = new float[8];
        int size;

        int add() {
            if (size == feat.length) grow();
            feat[size] = LEAF;
            left[size] = -1;
            right[size] = -1;
            thr[size] = 0;
            leaf[size] = 0;
            return size++;
        }

        void setLeaf(int i, float v) {
            feat[i] = LEAF;
            leaf[i] = v;
        }

        void setSplit(int i, int f, float t) {
            feat[i] = f;
            thr[i] = t;
        }

        void setLeft(int i, int c) {
            left[i] = c;
        }

        void setRight(int i, int c) {
            right[i] = c;
        }

        int nodeCount() {
            return size;
        }

        private void grow() {
            int n = feat.length * 2;
            feat = Arrays.copyOf(feat, n);
            thr = Arrays.copyOf(thr, n);
            left = Arrays.copyOf(left, n);
            right = Arrays.copyOf(right, n);
            leaf = Arrays.copyOf(leaf, n);
        }
    }
}