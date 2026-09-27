package com.potatotv.paccclient.detection.federated;

import com.potatotv.paccclient.detection.FeatureSchema;
import com.potatotv.paccclient.detection.FeatureVector;

import java.util.List;

/**
 * DF §4.1.2 端侧本地训练器：从「本地已采集的样本」计算模型梯度（模型增量），<b>原始数据不出设备</b>——
 * 唯一向外传递的是 {@link TrainingResult#gradient()} 与样本数。
 *
 * <p>本地模型是 {@code FeatureSchema} 维度上的 MLP 自编码器（见 {@link FederatedParameterLayout}），
 * 训练目标为重构均方误差。梯度按反向传播逐样本累加后取均值，运算量 O(样本数 × 参数量)，与云端无关。
 * 样本复用既有 {@link FeatureVector}（{@link FeatureVector#toArray()} 即 {@code FeatureSchema} 键序），
 * 不新造平行特征表。</p>
 *
 * <p>全局参数以 {@link #seedParameters(double[])} 注入：本地训练总是从「当前全局模型」出发算梯度，
 * 未注入时为全零初始参数（等价于从零模型开始），保证行为可复现。</p>
 */
public final class LocalGradientTrainer {

    private final int featureDim;
    private final int hidden;
    private final int w1Offset;
    private final int b1Offset;
    private final int w2Offset;
    private final int b2Offset;

    private double[] params;

    /**
     * @param featureDim 特征维度（应等于 {@link FeatureSchema#size()}）
     * @param hidden     自编码器隐藏层维度
     */
    public LocalGradientTrainer(int featureDim, int hidden) {
        if (featureDim <= 0 || hidden <= 0) {
            throw new IllegalArgumentException("训练器维度必须为正: featureDim=" + featureDim + " hidden=" + hidden);
        }
        this.featureDim = featureDim;
        this.hidden = hidden;
        this.w1Offset = 0;
        this.b1Offset = hidden * featureDim;
        this.w2Offset = b1Offset + hidden;
        this.b2Offset = w2Offset + featureDim * hidden;
        this.params = new double[FederatedParameterLayout.parameterCount(featureDim, hidden)];
    }

    /** 以默认维度构造（{@code FeatureSchema} 178 维，隐藏层 8）。 */
    public LocalGradientTrainer() {
        this(FeatureSchema.size(), 8);
    }

    /** 梯度向量维度（= {@link FederatedParameterLayout#parameterCount(int, int)}）。 */
    public int gradientDim() {
        return params.length;
    }

    /**
     * 注入当前全局参数（联邦下发的模型），本地训练据此计算增量。
     *
     * @throws IllegalArgumentException 长度与布局不符
     */
    public void seedParameters(double[] globalParams) {
        if (globalParams == null || globalParams.length != params.length) {
            throw new IllegalArgumentException("全局参数长度 " + (globalParams == null ? 0 : globalParams.length)
                    + " 与本地模型 " + params.length + " 不匹配");
        }
        this.params = globalParams.clone();
    }

    /**
     * 从本地样本计算梯度。
     *
     * @param samples 本地特征样本（{@code null} 元素被跳过）
     * @return 梯度向量（长度 {@link #gradientDim()}）、参与样本数、平均重构损失；无有效样本时梯度全零
     */
    public TrainingResult train(List<FeatureVector> samples) {
        double[] grad = new double[params.length];
        int n = 0;
        double lossSum = 0;
        double[] x = new double[featureDim];
        double[] a1 = new double[hidden];
        double[] y = new double[featureDim];
        double[] dy = new double[featureDim];
        double[] dz1 = new double[hidden];

        if (samples != null) {
            for (FeatureVector fv : samples) {
                if (fv == null) {
                    continue;
                }
                double[] arr = fv.toArray();
                System.arraycopy(arr, 0, x, 0, Math.min(arr.length, featureDim));

                // 前向：隐藏层 tanh，输出层线性
                for (int k = 0; k < hidden; k++) {
                    double z = params[b1Offset + k];
                    int base = w1Offset + k * featureDim;
                    for (int p = 0; p < featureDim; p++) {
                        z += params[base + p] * x[p];
                    }
                    a1[k] = Math.tanh(z);
                }
                double se = 0;
                for (int j = 0; j < featureDim; j++) {
                    double z = params[b2Offset + j];
                    int base = w2Offset + j * hidden;
                    for (int k = 0; k < hidden; k++) {
                        z += params[base + k] * a1[k];
                    }
                    y[j] = z;
                    double diff = x[j] - y[j];
                    se += diff * diff;
                    dy[j] = -2.0 / featureDim * diff;
                }
                lossSum += se / featureDim;

                // 反向：输出层 → 隐藏层
                for (int j = 0; j < featureDim; j++) {
                    int base = w2Offset + j * hidden;
                    for (int k = 0; k < hidden; k++) {
                        grad[base + k] += dy[j] * a1[k];
                    }
                    grad[b2Offset + j] += dy[j];
                }
                for (int k = 0; k < hidden; k++) {
                    double s = 0;
                    for (int j = 0; j < featureDim; j++) {
                        s += params[w2Offset + j * hidden + k] * dy[j];
                    }
                    dz1[k] = s * (1 - a1[k] * a1[k]);
                }
                for (int k = 0; k < hidden; k++) {
                    int base = w1Offset + k * featureDim;
                    for (int p = 0; p < featureDim; p++) {
                        grad[base + p] += dz1[k] * x[p];
                    }
                    grad[b1Offset + k] += dz1[k];
                }
                n++;
            }
        }

        if (n == 0) {
            return new TrainingResult(grad, 0, 0.0);
        }
        for (int i = 0; i < grad.length; i++) {
            grad[i] /= n;
        }
        return new TrainingResult(grad, n, lossSum / n);
    }

    /** 一次本地训练的结果：梯度（模型增量）、参与样本数、平均损失。 */
    public record TrainingResult(double[] gradient, int sampleCount, double loss) {
    }
}