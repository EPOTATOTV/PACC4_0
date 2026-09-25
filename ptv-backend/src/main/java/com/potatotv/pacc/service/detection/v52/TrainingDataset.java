package com.potatotv.pacc.service.detection.v52;

import com.potatotv.pacc.domain.FeatureVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

/**
 * v5.2 §6.1 训练数据集：由人工复核结论（{@code feature_xxx} 特征 JSON + 标签）构建的特征矩阵。
 *
 * <p><b>特征列顺序稳定且可复现</b>：取全部样本出现过的 {@code feature_xxx} 键的并集，按字典序升序排列，
 * 再剔除<b>常量列</b>（该列在全部样本上取值完全相同：对判别没有贡献，且树模型无法在其上分裂）。
 * 样本缺某键时按 {@link FeatureVector#get} 的约定补 0。因此同一批复核数据每次构建得到的列顺序一致，
 * 训练出的模型维度（{@code featureDim}）与库表登记的蓝本可对齐。</p>
 */
public final class TrainingDataset {

    /** 一条已复核样本：特征 JSON（含 {@code feature_xxx} 键）+ 标签（1=确认作弊，0=人工判定误报）。 */
    public record LabeledSample(String featuresJson, double label) {
    }

    /** 训练 / 留出划分结果（两侧列顺序一致）。 */
    public record Split(TrainingDataset train, TrainingDataset test) {
    }

    private final List<String> featureKeys;
    private final double[][] features;
    private final double[] labels;

    private TrainingDataset(List<String> featureKeys, double[][] features, double[] labels) {
        this.featureKeys = featureKeys;
        this.features = features;
        this.labels = labels;
    }

    /** 构建特征矩阵；样本为空时抛 {@link IllegalArgumentException}。 */
    public static TrainingDataset build(List<LabeledSample> samples) {
        if (samples == null || samples.isEmpty()) {
            throw new IllegalArgumentException("复核样本为空，无法构建训练集");
        }
        List<Map<String, Double>> parsed = new ArrayList<>(samples.size());
        Set<String> union = new TreeSet<>();
        for (LabeledSample s : samples) {
            Map<String, Double> m = FeatureVector.fromJson(s.featuresJson()).asMap();
            parsed.add(m);
            union.addAll(m.keySet());
        }
        List<String> kept = new ArrayList<>();
        for (String key : union) {
            if (!isConstant(parsed, key)) {
                kept.add(key);
            }
        }
        int n = samples.size();
        int d = kept.size();
        double[][] x = new double[n][d];
        double[] y = new double[n];
        for (int i = 0; i < n; i++) {
            y[i] = samples.get(i).label();
            Map<String, Double> row = parsed.get(i);
            for (int j = 0; j < d; j++) {
                Double v = row.get(kept.get(j));
                x[i][j] = v == null ? 0.0 : v;
            }
        }
        return new TrainingDataset(List.copyOf(kept), x, y);
    }

    /** 该列是否在全部样本上取值相同（缺失按 0 计）。 */
    private static boolean isConstant(List<Map<String, Double>> rows, String key) {
        double first = rows.get(0).getOrDefault(key, 0.0);
        for (Map<String, Double> row : rows) {
            if (Double.compare(row.getOrDefault(key, 0.0), first) != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 按给定比例切分训练 / 留出集（种子驱动的定序洗牌，结果可复现），留出集至少 1 条、最多 n-1 条。
     *
     * @param testRatio 留出比例 ∈ [0,1)
     * @param seed      洗牌种子
     */
    public Split split(double testRatio, long seed) {
        int n = labels.length;
        int testSize = (int) Math.round(n * Math.max(0.0, Math.min(testRatio, 1.0)));
        testSize = Math.max(0, Math.min(testSize, n - 1));

        int[] order = new int[n];
        for (int i = 0; i < n; i++) {
            order[i] = i;
        }
        Random rng = new Random(seed);
        for (int i = n - 1; i > 0; i--) {
            int j = rng.nextInt(i + 1);
            int tmp = order[i];
            order[i] = order[j];
            order[j] = tmp;
        }

        int trainSize = n - testSize;
        double[][] trainX = new double[trainSize][featureKeys.size()];
        double[] trainY = new double[trainSize];
        double[][] testX = new double[testSize][featureKeys.size()];
        double[] testY = new double[testSize];
        for (int i = 0; i < n; i++) {
            int src = order[i];
            if (i < testSize) {
                testX[i] = features[src];
                testY[i] = labels[src];
            } else {
                trainX[i - testSize] = features[src];
                trainY[i - testSize] = labels[src];
            }
        }
        return new Split(new TrainingDataset(featureKeys, trainX, trainY),
                new TrainingDataset(featureKeys, testX, testY));
    }

    /** 稳定特征列顺序（字典序升序，已剔除常量列）。 */
    public List<String> featureKeys() {
        return featureKeys;
    }

    /** 特征矩阵（n × featureDim，仅供训练消费，不做防御性拷贝）。 */
    public double[][] features() {
        return features;
    }

    /** 标签数组（1=作弊，0=正常）。 */
    public double[] labels() {
        return labels;
    }

    /** 列数（剔除常量列后的判别特征维度）。 */
    public int featureDim() {
        return featureKeys.size();
    }

    /** 样本行数。 */
    public int size() {
        return labels.length;
    }

    /** 正类（确认作弊）样本数。 */
    public int positiveCount() {
        int c = 0;
        for (double v : labels) {
            if (v >= 0.5) {
                c++;
            }
        }
        return c;
    }

    /** 负类（人工判定误报）样本数。 */
    public int negativeCount() {
        return size() - positiveCount();
    }
}