package com.potatotv.pacc.util.ml;

import java.util.Arrays;
import java.util.Random;

/**
 * v5.2 §6.1 后端训练侧自编码器（单隐层 tanh 自编码器，全批量梯度下降，纯 JDK 实现）。
 *
 * <p>与客户端 §2.1 的推理格式刻意保持一致（同一 {@code .paccm} 容器）：后端用「正常行为」样本
 * 训练重构模型，客户端加载后对本地特征做重构，重构误差越大表示越偏离正常流形（越可疑）。</p>
 *
 * <p>实现要点：</p>
 * <ul>
 *   <li>先按列标准化（零均值、单位标准差），使不同量纲的行为特征对重构误差贡献可比；</li>
 *   <li>编码 {@code a = tanh(W1·z + b1)}，解码 {@code z' = W2·a + b2}（线性输出层，MSE 损失）；</li>
 *   <li>每轮用全量样本的梯度均值更新一次，随机初始化由固定 seed 驱动 → 结果可复现；</li>
 *   <li>内存有界：只保留参数与单轮梯度累加器，不缓存样本矩阵。</li>
 * </ul>
 *
 * <p>权重区线性化约定（大端 float32）：{@code [0]=inputDim, [1]=hiddenDim, [2]=outputDim(==inputDim)}，
 * 随后依次是 {@code mean(inputDim)}、{@code scale(inputDim)}、{@code W1(input×hidden, 行优先)}、
 * {@code b1(hidden)}、{@code W2(hidden×output, 行优先)}、{@code b2(output)}。</p>
 */
public final class AutoencoderModel {

    /** 模型类型标识（写入 {@code .paccm} 容器的 modelType 字节）。 */
    public static final byte MODEL_TYPE = PaccModelFormat.TYPE_AUTOENCODER;

    private final int inputDim;
    private final int hiddenDim;
    private final double[] mean;
    private final double[] scale;
    private final double[][] w1;
    private final double[] b1;
    private final double[][] w2;
    private final double[] b2;

    private AutoencoderModel(int inputDim, int hiddenDim, double[] mean, double[] scale,
                             double[][] w1, double[] b1, double[][] w2, double[] b2) {
        this.inputDim = inputDim;
        this.hiddenDim = hiddenDim;
        this.mean = mean;
        this.scale = scale;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
    }

    /**
     * 在正常（合规）样本上拟合自编码器。
     *
     * @param samples 训练样本（n × d，d 固定）
     * @param hidden  隐层宽度（建议小于 d，形成压缩瓶颈）
     * @param epochs  训练轮数（0 表示只做随机初始化，不更新参数）
     * @param lr      学习率
     * @param seed    随机种子（固定值保证可复现）
     */
    public static AutoencoderModel fit(double[][] samples, int hidden, int epochs, double lr, long seed) {
        if (samples == null || samples.length == 0) {
            throw new IllegalArgumentException("训练样本为空");
        }
        int n = samples.length;
        int d = samples[0].length;
        if (d == 0) {
            throw new IllegalArgumentException("特征维度为 0");
        }
        if (hidden <= 0) {
            throw new IllegalArgumentException("隐层宽度必须为正：" + hidden);
        }
        if (epochs < 0) {
            throw new IllegalArgumentException("训练轮数不能为负：" + epochs);
        }
        if (lr <= 0) {
            throw new IllegalArgumentException("学习率必须为正：" + lr);
        }
        for (double[] row : samples) {
            for (int j = 0; j < d; j++) {
                if (!Double.isFinite(row[j])) {
                    throw new IllegalArgumentException("样本含非有限值");
                }
            }
        }

        double[] mean = new double[d];
        double[] scale = new double[d];
        for (double[] row : samples) {
            for (int j = 0; j < d; j++) {
                mean[j] += row[j];
            }
        }
        for (int j = 0; j < d; j++) {
            mean[j] /= n;
        }
        for (double[] row : samples) {
            for (int j = 0; j < d; j++) {
                scale[j] += Math.pow(row[j] - mean[j], 2);
            }
        }
        for (int j = 0; j < d; j++) {
            double sd = Math.sqrt(scale[j] / n);
            // 常数列标准差为 0：退化为不缩放（标准化后该列恒为 0），避免除零
            scale[j] = sd < 1e-9 ? 1.0 : sd;
        }

        // 标准化后的样本矩阵（训练期间复用，规模为 n×d，不随轮数增长）
        double[][] z = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                z[i][j] = (samples[i][j] - mean[j]) / scale[j];
            }
        }

        Random rng = new Random(seed);
        double[][] w1 = new double[d][hidden];
        double[] b1 = new double[hidden];
        double[][] w2 = new double[hidden][d];
        double[] b2 = new double[d];
        double init1 = Math.sqrt(1.0 / d);
        double init2 = Math.sqrt(1.0 / hidden);
        for (int i = 0; i < d; i++) {
            for (int k = 0; k < hidden; k++) {
                w1[i][k] = rng.nextGaussian() * init1;
            }
        }
        for (int k = 0; k < hidden; k++) {
            for (int j = 0; j < d; j++) {
                w2[k][j] = rng.nextGaussian() * init2;
            }
        }

        AutoencoderModel model = new AutoencoderModel(d, hidden, mean, scale, w1, b1, w2, b2);
        if (epochs == 0) {
            return model;
        }

        // 单轮梯度累加器（复用同一组数组，避免每轮/每样本分配）
        double[][] gw1 = new double[d][hidden];
        double[] gb1 = new double[hidden];
        double[][] gw2 = new double[hidden][d];
        double[] gb2 = new double[d];
        double[] a = new double[hidden];
        double[] out = new double[d];
        double[] dz = new double[hidden];

        for (int epoch = 0; epoch < epochs; epoch++) {
            for (int i = 0; i < d; i++) {
                Arrays.fill(gw1[i], 0.0);
            }
            for (int k = 0; k < hidden; k++) {
                Arrays.fill(gw2[k], 0.0);
            }
            Arrays.fill(gb1, 0.0);
            Arrays.fill(gb2, 0.0);

            for (int r = 0; r < n; r++) {
                double[] row = z[r];
                model.forward(row, a, out);
                for (int j = 0; j < d; j++) {
                    gb2[j] += out[j] - row[j];
                }
                for (int k = 0; k < hidden; k++) {
                    double delta = 0;
                    for (int j = 0; j < d; j++) {
                        double diff = out[j] - row[j];
                        gw2[k][j] += diff * a[k];
                        delta += diff * w2[k][j];
                    }
                    // tanh 导数：(1 - a²)
                    dz[k] = (1 - a[k] * a[k]) * delta;
                }
                for (int i2 = 0; i2 < d; i2++) {
                    for (int k = 0; k < hidden; k++) {
                        gw1[i2][k] += dz[k] * row[i2];
                    }
                }
                for (int k = 0; k < hidden; k++) {
                    gb1[k] += dz[k];
                }
            }

            double step = lr / n;
            for (int i2 = 0; i2 < d; i2++) {
                for (int k = 0; k < hidden; k++) {
                    w1[i2][k] -= step * gw1[i2][k];
                }
            }
            for (int k = 0; k < hidden; k++) {
                b1[k] -= step * gb1[k];
                for (int j = 0; j < d; j++) {
                    w2[k][j] -= step * gw2[k][j];
                }
            }
            for (int j = 0; j < d; j++) {
                b2[j] -= step * gb2[j];
            }
        }
        return model;
    }

    /**
     * 重构误差 {@code ||z - z'||₂}（标准化空间，越大越偏离正常流形）。
     */
    public double reconstructionError(double[] x) {
        if (x == null || x.length != inputDim) {
            throw new IllegalArgumentException("特征维度不匹配，期望 " + inputDim + "，实际 " + (x == null ? 0 : x.length));
        }
        double[] z = new double[inputDim];
        for (int j = 0; j < inputDim; j++) {
            z[j] = (x[j] - mean[j]) / scale[j];
        }
        double[] a = new double[hiddenDim];
        double[] out = new double[inputDim];
        forward(z, a, out);
        double err = 0;
        for (int j = 0; j < inputDim; j++) {
            double diff = out[j] - z[j];
            err += diff * diff;
        }
        return Math.sqrt(err);
    }

    /** 输入特征维度。 */
    public int inputDim() {
        return inputDim;
    }

    /** 隐层宽度。 */
    public int hiddenDim() {
        return hiddenDim;
    }

    /** 编码 + 解码（复用外部传入的暂存数组）。 */
    private void forward(double[] z, double[] a, double[] out) {
        for (int k = 0; k < hiddenDim; k++) {
            double s = b1[k];
            for (int i = 0; i < inputDim; i++) {
                s += w1[i][k] * z[i];
            }
            a[k] = Math.tanh(s);
        }
        for (int j = 0; j < inputDim; j++) {
            double s = b2[j];
            for (int k = 0; k < hiddenDim; k++) {
                s += w2[k][j] * a[k];
            }
            out[j] = s;
        }
    }

    /** 编码为 {@code .paccm} 模型字节（含尾部 SHA-256 校验和）。 */
    public byte[] toPaccmBytes() {
        return PaccModelFormat.encode(MODEL_TYPE, inputDim, flatten());
    }

    /** 按线性化约定展开为 float32 权重区。 */
    private float[] flatten() {
        int total = 3 + inputDim * 2 + hiddenDim
                + inputDim * hiddenDim + hiddenDim * inputDim + inputDim;
        float[] out = new float[total];
        int p = 0;
        out[p++] = inputDim;
        out[p++] = hiddenDim;
        out[p++] = inputDim;
        for (double v : mean) {
            out[p++] = (float) v;
        }
        for (double v : scale) {
            out[p++] = (float) v;
        }
        for (int i = 0; i < inputDim; i++) {
            for (int k = 0; k < hiddenDim; k++) {
                out[p++] = (float) w1[i][k];
            }
        }
        for (double v : b1) {
            out[p++] = (float) v;
        }
        for (int k = 0; k < hiddenDim; k++) {
            for (int j = 0; j < inputDim; j++) {
                out[p++] = (float) w2[k][j];
            }
        }
        for (double v : b2) {
            out[p++] = (float) v;
        }
        return out;
    }
}