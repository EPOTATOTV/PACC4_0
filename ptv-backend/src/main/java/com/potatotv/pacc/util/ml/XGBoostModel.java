package com.potatotv.pacc.util.ml;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * v5.2 §6.1 后端训练侧 XGBoost（二分类逻辑损失梯度提升树，纯 JDK 实现，无第三方 ML 依赖）。
 *
 * <p>与客户端 §2.1 的推理格式刻意保持一致（同一 {@code .paccm} 容器）：后端训练并产出模型字节，
 * 客户端按相同布局反序列化后本地推理。实现要点：</p>
 * <ul>
 *   <li>二阶（牛顿）提升：叶子权重 {@code w = -Σg / (Σh + λ)}，分裂增益按
 *       {@code 0.5*(GL²/(HL+λ) + GR²/(HR+λ) - (GL+GR)²/(HL+HR+λ))} 评估；</li>
 *   <li>分位候选分裂：每个特征只在该节点样本的分位点（最多 {@value #SPLIT_CANDIDATES} 个）上试分裂，
 *       避免遍历全部取值；</li>
 *   <li>按树做特征子采样（colsample）与随机种子驱动，固定 seed 下结果完全可复现；</li>
 *   <li>内存有界：只保存树结构数组，训练过程不缓存中间矩阵。</li>
 * </ul>
 *
 * <p>权重区线性化约定（大端 float32，供 {@link PaccModelFormat} 承载）：
 * {@code [0]=baseScore, [1]=treeCount, [2]=maxDepth}，随后每棵树先写 1 个 {@code nodeCount}，
 * 再按前序遍历逐节点写 4 个值 {@code feature(-1 表示叶子), threshold, weight, rightChildIndex}
 * （左孩子恒为「当前下标+1」，叶子 rightChild = -1）。</p>
 */
public final class XGBoostModel {

    /** 模型类型标识（写入 {@code .paccm} 容器的 modelType 字节）。 */
    public static final byte MODEL_TYPE = PaccModelFormat.TYPE_XGBOOST;

    /** L2 正则系数（叶子权重与增益计算共用）。 */
    private static final double REG_LAMBDA = 1.0;
    /** 每个特征的分位候选分裂点数量。 */
    private static final int SPLIT_CANDIDATES = 16;
    /** 每棵树的特征子采样比例（colsample_bytree）。 */
    private static final double COLSAMPLE_RATIO = 0.8;
    /** 最小分裂增益，低于该值不再分裂（避免噪声分裂）。 */
    private static final double MIN_SPLIT_GAIN = 1e-9;

    private final int featureDim;
    private final int maxDepth;
    private final double baseScore;
    private final Tree[] trees;

    /** 树节点：{@code feature < 0} 表示叶子。 */
    private static final class Node {
        int feature = -1;
        double threshold;
        double weight;
        double gain;
        int right = -1;
    }

    /** 树：节点按前序存储在列表里，左孩子恒为「当前下标 + 1」。 */
    private static final class Tree {
        final List<Node> nodes = new ArrayList<>();
    }

    private XGBoostModel(int featureDim, int maxDepth, double baseScore, Tree[] trees) {
        this.featureDim = featureDim;
        this.maxDepth = maxDepth;
        this.baseScore = baseScore;
        this.trees = trees;
    }

    /**
     * 训练二分类提升树。
     *
     * @param x           样本矩阵（n × d，d 固定）
     * @param y           标签（取值 0 或 1）
     * @param nTrees      树的数量
     * @param maxDepth    单棵树最大深度（0 表示只有根节点）
     * @param learningRate 学习率（收缩系数，&gt; 0）
     * @param seed        随机种子（固定值保证可复现）
     */
    public static XGBoostModel train(double[][] x, double[] y, int nTrees, int maxDepth,
                                     double learningRate, long seed) {
        if (x == null || x.length == 0) {
            throw new IllegalArgumentException("训练样本为空");
        }
        int n = x.length;
        int d = x[0].length;
        if (d == 0) {
            throw new IllegalArgumentException("特征维度为 0");
        }
        if (y == null || y.length != n) {
            throw new IllegalArgumentException("标签数量与样本数不一致");
        }
        if (nTrees <= 0) {
            throw new IllegalArgumentException("树的数量必须为正：" + nTrees);
        }
        if (maxDepth < 0) {
            throw new IllegalArgumentException("树深度不能为负：" + maxDepth);
        }
        if (learningRate <= 0) {
            throw new IllegalArgumentException("学习率必须为正：" + learningRate);
        }
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                if (!Double.isFinite(x[i][j])) {
                    throw new IllegalArgumentException("特征含非有限值：row=" + i + " col=" + j);
                }
            }
            if (y[i] < 0 || y[i] > 1) {
                throw new IllegalArgumentException("标签必须为 0 或 1：row=" + i);
            }
        }

        double base = baseScoreOf(y);
        double[] margin = new double[n];
        Arrays.fill(margin, base);

        Random rng = new Random(seed);
        Tree[] trees = new Tree[nTrees];
        for (int t = 0; t < nTrees; t++) {
            double[] g = new double[n];
            double[] h = new double[n];
            for (int i = 0; i < n; i++) {
                double p = sigmoid(margin[i]);
                g[i] = p - y[i];
                h[i] = p * (1 - p);
            }
            int[] cols = sampleColumns(d, rng);
            Tree tree = new Tree();
            buildNode(tree, x, g, h, range(n), 0, maxDepth, cols, rng);
            // 收缩：叶子权重乘学习率后再更新预测，防止单棵树过拟合
            for (Node node : tree.nodes) {
                if (node.feature < 0) {
                    node.weight *= learningRate;
                }
            }
            for (int i = 0; i < n; i++) {
                margin[i] += leafWeight(tree, x[i]);
            }
            trees[t] = tree;
        }
        return new XGBoostModel(d, maxDepth, base, trees);
    }

    /** 作弊概率预测值 ∈ [0,1]（logistic）。 */
    public double predict(double[] x) {
        return sigmoid(decision(x));
    }

    /** 未过 logistic 的原始得分（baseScore + 各树叶子权重之和）。 */
    public double decision(double[] x) {
        if (x == null || x.length != featureDim) {
            throw new IllegalArgumentException("特征维度不匹配，期望 " + featureDim + "，实际 " + (x == null ? 0 : x.length));
        }
        double s = baseScore;
        for (Tree tree : trees) {
            s += leafWeight(tree, x);
        }
        return s;
    }

    /**
     * 特征贡献归因：统计决策路径上各分裂节点的增益，按特征累加后归一化（各维之和为 1，无分裂时为全 0）。
     * 用于运营侧解释「本次判定主要看哪些行为特征」。
     */
    public double[] featureContributions(double[] x) {
        if (x == null || x.length != featureDim) {
            throw new IllegalArgumentException("特征维度不匹配，期望 " + featureDim + "，实际 " + (x == null ? 0 : x.length));
        }
        double[] contrib = new double[featureDim];
        for (Tree tree : trees) {
            int idx = 0;
            while (true) {
                Node node = tree.nodes.get(idx);
                if (node.feature < 0) {
                    break;
                }
                contrib[node.feature] += node.gain;
                idx = x[node.feature] < node.threshold ? idx + 1 : node.right;
            }
        }
        double sum = 0;
        for (double c : contrib) {
            sum += c;
        }
        if (sum > 0) {
            for (int i = 0; i < contrib.length; i++) {
                contrib[i] /= sum;
            }
        }
        return contrib;
    }

    /** 训练时使用的特征维度。 */
    public int featureDim() {
        return featureDim;
    }

    /** 树的数量。 */
    public int treeCount() {
        return trees.length;
    }

    /** 编码为 {@code .paccm} 模型字节（含尾部 SHA-256 校验和）。 */
    public byte[] toPaccmBytes(int featureDim) {
        if (featureDim != this.featureDim) {
            throw new IllegalArgumentException("特征维度与训练时不一致，训练时 " + this.featureDim + "，传入 " + featureDim);
        }
        return PaccModelFormat.encode(MODEL_TYPE, featureDim, flatten());
    }

    /** 按线性化约定展开为 float32 权重区。 */
    private float[] flatten() {
        int total = 3;
        for (Tree tree : trees) {
            total += 1 + tree.nodes.size() * 4;
        }
        float[] out = new float[total];
        int p = 0;
        out[p++] = (float) baseScore;
        out[p++] = trees.length;
        out[p++] = maxDepth;
        for (Tree tree : trees) {
            out[p++] = tree.nodes.size();
            for (int i = 0; i < tree.nodes.size(); i++) {
                Node node = tree.nodes.get(i);
                out[p++] = node.feature;
                out[p++] = (float) node.threshold;
                out[p++] = (float) node.weight;
                out[p++] = node.right;
            }
        }
        return out;
    }

    // ------------------------------ 训练内部实现 ------------------------------

    /** 前序构建：当前节点先入列表，随后递归左、右子树（左孩子恒为 self+1）。 */
    private static int buildNode(Tree tree, double[][] x, double[] g, double[] h, int[] idx,
                                 int depth, int maxDepth, int[] cols, Random rng) {
        Node node = new Node();
        int self = tree.nodes.size();
        tree.nodes.add(node);
        double sumG = 0;
        double sumH = 0;
        for (int i : idx) {
            sumG += g[i];
            sumH += h[i];
        }
        node.weight = -sumG / (sumH + REG_LAMBDA);
        if (depth >= maxDepth || idx.length < 2) {
            return self;
        }
        Split best = bestSplit(x, g, h, idx, cols);
        if (best == null) {
            return self;
        }
        node.feature = best.feature;
        node.threshold = best.threshold;
        node.gain = best.gain;
        int[] left = new int[best.leftCount];
        int[] right = new int[idx.length - best.leftCount];
        int li = 0;
        int ri = 0;
        for (int i : idx) {
            if (x[i][best.feature] < best.threshold) {
                left[li++] = i;
            } else {
                right[ri++] = i;
            }
        }
        buildNode(tree, x, g, h, left, depth + 1, maxDepth, cols, rng);
        node.right = buildNode(tree, x, g, h, right, depth + 1, maxDepth, cols, rng);
        return self;
    }

    /** 最优分裂：在给定特征子集上按分位候选点搜索最大增益。 */
    private static Split bestSplit(double[][] x, double[] g, double[] h, int[] idx, int[] cols) {
        Split best = null;
        double[] values = new double[idx.length];
        for (int f : cols) {
            for (int i = 0; i < idx.length; i++) {
                values[i] = x[idx[i]][f];
            }
            Arrays.sort(values);
            if (values[0] >= values[values.length - 1]) {
                continue; // 该特征在节点样本上无变化
            }
            int candidates = Math.min(SPLIT_CANDIDATES, values.length - 1);
            for (int c = 1; c <= candidates; c++) {
                int pos = (int) ((long) c * values.length / (candidates + 1));
                double threshold = values[pos];
                if (threshold <= values[0]) {
                    continue;
                }
                double gl = 0;
                double hl = 0;
                double gr = 0;
                double hr = 0;
                int leftCount = 0;
                for (int i : idx) {
                    if (x[i][f] < threshold) {
                        gl += g[i];
                        hl += h[i];
                        leftCount++;
                    } else {
                        gr += g[i];
                        hr += h[i];
                    }
                }
                if (leftCount == 0 || leftCount == idx.length) {
                    continue;
                }
                double gain = 0.5 * (gl * gl / (hl + REG_LAMBDA)
                        + gr * gr / (hr + REG_LAMBDA)
                        - (gl + gr) * (gl + gr) / (hl + hr + REG_LAMBDA));
                if (gain > MIN_SPLIT_GAIN && (best == null || gain > best.gain)) {
                    best = new Split(f, threshold, gain, leftCount);
                }
            }
        }
        return best;
    }

    /** 候选分裂点结果。 */
    private record Split(int feature, double threshold, double gain, int leftCount) {
    }

    /** 按 colsample 比例抽样特征子集（Fisher-Yates，种子驱动）。 */
    private static int[] sampleColumns(int d, Random rng) {
        int[] all = range(d);
        int keep = Math.max(1, (int) Math.round(d * COLSAMPLE_RATIO));
        for (int i = d - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = all[i];
            all[i] = all[j];
            all[j] = tmp;
        }
        return Arrays.copyOf(all, Math.min(keep, d));
    }

    private static int[] range(int size) {
        int[] out = new int[size];
        for (int i = 0; i < size; i++) {
            out[i] = i;
        }
        return out;
    }

    private static double leafWeight(Tree tree, double[] x) {
        int idx = 0;
        while (true) {
            Node node = tree.nodes.get(idx);
            if (node.feature < 0) {
                return node.weight;
            }
            idx = x[node.feature] < node.threshold ? idx + 1 : node.right;
        }
    }

    /** 先验 log-odds（正类占比裁剪到 [1e-3, 1-1e-3] 防止 ±∞）。 */
    private static double baseScoreOf(double[] y) {
        double mean = 0;
        for (double v : y) {
            mean += v;
        }
        mean /= y.length;
        double p = Math.max(1e-3, Math.min(1 - 1e-3, mean));
        return Math.log(p / (1 - p));
    }

    private static double sigmoid(double z) {
        if (z >= 0) {
            double e = Math.exp(-z);
            return 1 / (1 + e);
        }
        double e = Math.exp(z);
        return e / (1 + e);
    }
}