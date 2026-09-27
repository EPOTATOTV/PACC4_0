package com.potatotv.paccclient.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;

/**
 * 纯 Java MLP 自编码器（featureDim → hidden → featureDim），零第三方 ML 依赖（文档 §2.1.2 / §2.3）。
 *
 * <p>隐藏层 tanh 激活、输出层线性；以重构均方误差 {@link #reconstructionError(double[])} 作为
 * 偏离正常流形的异常信号。权重由 PTV 训练流水线（文档 §6.1）下发，本类同时提供纯 SGD 反向传播的
 * {@link #fit} 以便端侧/测试复现小模型。</p>
 *
 * <p>权重自描述二进制布局（大端序）：</p>
 * <pre>
 * [4B]  magic "AE01"
 * [4B]  featureDim
 * [4B]  hidden
 * [H*d float] W1（行优先，hidden × featureDim）
 * [H   float] b1
 * [d*H float] W2（featureDim × hidden）
 * [d   float] b2
 * </pre>
 */
public final class AutoencoderModel {

    private static final byte[] MAGIC = {'A', 'E', '0', '1'};
    /** 权重初始化/更新缩放，避免 sigmoid/tanh 饱和与数值发散。 */
    private static final double INIT_SCALE = 0.1;

    private final int featureDim;
    private final int hidden;
    private final double[] w1; // hidden × featureDim
    private final double[] b1; // hidden
    private final double[] w2; // featureDim × hidden
    private final double[] b2; // featureDim

    private AutoencoderModel(int featureDim, int hidden, double[] w1, double[] b1, double[] w2, double[] b2) {
        this.featureDim = featureDim;
        this.hidden = hidden;
        this.w1 = w1;
        this.b1 = b1;
        this.w2 = w2;
        this.b2 = b2;
    }

    /** 重构：先 tanh 隐藏层，再线性输出。 */
    public double[] reconstruct(double[] x) {
        double[] a1 = hiddenActivations(x);
        double[] y = new double[featureDim];
        int h = hidden;
        for (int j = 0; j < featureDim; j++) {
            double z = b2[j];
            int base = j * h;
            for (int k = 0; k < h; k++) z += w2[base + k] * a1[k];
            y[j] = z;
        }
        return y;
    }

    /** 重构均方误差（MSE），越大表示越偏离正常样本流形。 */
    public double reconstructionError(double[] x) {
        double[] y = reconstruct(x);
        double s = 0;
        for (int j = 0; j < featureDim; j++) {
            double v = j < x.length ? x[j] : 0.0;
            double d = v - y[j];
            s += d * d;
        }
        return s / featureDim;
    }

    /** 序列化为自描述字节（见类注释布局）。 */
    public byte[] serialize() {
        int cap = 12 + (w1.length + b1.length + w2.length + b2.length) * 4;
        ByteBuffer bb = ByteBuffer.allocate(cap).order(ByteOrder.BIG_ENDIAN);
        bb.put(MAGIC);
        bb.putInt(featureDim);
        bb.putInt(hidden);
        put(bb, w1);
        put(bb, b1);
        put(bb, w2);
        put(bb, b2);
        return bb.array();
    }

    /** 反序列化；{@code featureDim} 为调用方声明的特征维度，须与载荷一致。 */
    public static AutoencoderModel deserialize(byte[] weights, int featureDim) {
        if (weights == null || weights.length < 12) throw new IllegalArgumentException("自编码器权重数据过短");
        for (int i = 0; i < 4; i++) {
            if (weights[i] != MAGIC[i]) throw new IllegalArgumentException("自编码器权重魔数非法");
        }
        ByteBuffer bb = ByteBuffer.wrap(weights).order(ByteOrder.BIG_ENDIAN);
        bb.position(4);
        int fd = bb.getInt();
        int h = bb.getInt();
        if (fd != featureDim) throw new IllegalArgumentException("特征维度不匹配: " + fd + " != " + featureDim);
        if (h <= 0 || h > 1 << 16) throw new IllegalArgumentException("隐藏层维度非法: " + h);
        int need = 12 + (fd * h + h + fd * h + fd) * 4;
        if (weights.length != need) throw new IllegalArgumentException("权重数据长度不一致");
        double[] w1 = get(bb, h * fd);
        double[] b1 = get(bb, h);
        double[] w2 = get(bb, fd * h);
        double[] b2 = get(bb, fd);
        return new AutoencoderModel(fd, h, w1, b1, w2, b2);
    }

    /**
     * 纯 SGD 反向传播拟合自编码器（MSE 损失）。
     * <p>逐样本在线更新，输出层线性、隐藏层 tanh；同一 seed 下初始化与样本打乱顺序可复现。</p>
     *
     * @param samples n × d 训练样本（须为同一维度）
     * @param hidden  隐藏层维度
     * @param epochs  训练轮数（0 表示只做初始化）
     * @param lr      学习率
     * @param seed    随机种子
     */
    public static AutoencoderModel fit(double[][] samples, int hidden, int epochs, double lr, long seed) {
        if (samples == null || samples.length == 0 || samples[0] == null || samples[0].length == 0) {
            throw new IllegalArgumentException("训练样本非法");
        }
        int d = samples[0].length;
        int h = Math.max(1, hidden);
        Random rng = new Random(seed);
        double[] w1 = new double[h * d];
        double[] b1 = new double[h];
        double[] w2 = new double[d * h];
        double[] b2 = new double[d];
        for (int i = 0; i < w1.length; i++) w1[i] = rng.nextGaussian() * INIT_SCALE;
        for (int i = 0; i < w2.length; i++) w2[i] = rng.nextGaussian() * INIT_SCALE;

        int n = samples.length;
        int[] order = new int[n];
        for (int i = 0; i < n; i++) order[i] = i;
        for (int e = 0; e < epochs; e++) {
            for (int i = n - 1; i > 0; i--) {
                int j = rng.nextInt(i + 1);
                int tmp = order[i];
                order[i] = order[j];
                order[j] = tmp;
            }
            for (int oi = 0; oi < n; oi++) {
                trainOne(samples[order[oi]], d, h, lr, w1, b1, w2, b2);
            }
        }
        return new AutoencoderModel(d, h, w1, b1, w2, b2);
    }

    /** 单样本前向 + 反向 + 在线更新。 */
    private static void trainOne(double[] x, int d, int h, double lr,
                                 double[] w1, double[] b1, double[] w2, double[] b2) {
        double[] a1 = new double[h];
        for (int k = 0; k < h; k++) {
            double z = b1[k];
            int base = k * d;
            for (int p = 0; p < d; p++) z += w1[base + p] * (p < x.length ? x[p] : 0.0);
            a1[k] = Math.tanh(z);
        }
        double[] y = new double[d];
        for (int j = 0; j < d; j++) {
            double z = b2[j];
            int base = j * h;
            for (int k = 0; k < h; k++) z += w2[base + k] * a1[k];
            y[j] = z;
        }
        // dL/dy = -2/d * (x - y)
        double[] dy = new double[d];
        for (int j = 0; j < d; j++) {
            double xv = j < x.length ? x[j] : 0.0;
            dy[j] = -2.0 / d * (xv - y[j]);
        }
        double[] dz1 = new double[h];
        for (int k = 0; k < h; k++) {
            double s = 0;
            for (int j = 0; j < d; j++) s += w2[j * h + k] * dy[j];
            dz1[k] = s * (1 - a1[k] * a1[k]);
        }
        for (int j = 0; j < d; j++) {
            int base = j * h;
            for (int k = 0; k < h; k++) w2[base + k] -= lr * dy[j] * a1[k];
            b2[j] -= lr * dy[j];
        }
        for (int k = 0; k < h; k++) {
            int base = k * d;
            for (int p = 0; p < d; p++) w1[base + p] -= lr * dz1[k] * (p < x.length ? x[p] : 0.0);
            b1[k] -= lr * dz1[k];
        }
    }

    private double[] hiddenActivations(double[] x) {
        double[] a1 = new double[hidden];
        for (int k = 0; k < hidden; k++) {
            double z = b1[k];
            int base = k * featureDim;
            for (int p = 0; p < featureDim; p++) z += w1[base + p] * (p < x.length ? x[p] : 0.0);
            a1[k] = Math.tanh(clip(z));
        }
        return a1;
    }

    private static void put(ByteBuffer bb, double[] v) {
        for (double x : v) bb.putFloat((float) x);
    }

    private static double[] get(ByteBuffer bb, int len) {
        double[] v = new double[len];
        for (int i = 0; i < len; i++) v[i] = bb.getFloat();
        return v;
    }

    private static double clip(double z) {
        return Math.max(-30, Math.min(30, z));
    }

    public int featureDim() {
        return featureDim;
    }
}