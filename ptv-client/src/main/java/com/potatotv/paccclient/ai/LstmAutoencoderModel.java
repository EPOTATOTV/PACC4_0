package com.potatotv.paccclient.ai;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 纯 Java 单层 LSTM 编码器 + LSTM 解码器的时序自编码器，零第三方 ML 依赖（文档 §2.1.2 / §3.1.3）。
 *
 * <p>编码器逐时刻处理扁平序列 {@code [t0_f0..t0_fF, t1_f0..]}，取末态 (h,c) 作为上下文；
 * 解码器以该状态初始化，并自回归（第 t 步输入为上一步重构输出，首步为零向量）逐时刻重构序列，
 * 输出层线性。异常分 {@link #anomalyScore(double[])} 为输入与重构的均方误差。</p>
 *
 * <p>门控标准实现：{@code z = Wx·x + Wh·h + b}（4H 维，依次为 input/forget/output/cell），
 * {@code i,f,o = sigmoid(·)}、{@code g = tanh(·)}，{@code c = f⊙c + i⊙g}、{@code h = o⊙tanh(c)}；
 * sigmoid 输入与 cell 状态均做数值裁剪，避免溢出发散。</p>
 *
 * <p>权重来自 PTV 训练流水线（文档 §6.1）灰度下发，本类不提供端侧训练（单测环境下真实训练过慢）。
 * 自描述二进制布局（大端序）：</p>
 * <pre>
 * [4B] magic "LS01"
 * [4B] timesteps  [4B] featuresPerStep  [4B] hidden
 * 编码器: encWx(4H×F) encWh(4H×H) encB(4H)   均为 float32
 * 解码器: decWx(4H×F) decWh(4H×H) decB(4H)
 * 输出层: outW(F×H) outB(F)
 * </pre>
 */
public final class LstmAutoencoderModel {

    private static final byte[] MAGIC = {'L', 'S', '0', '1'};
    private static final int GATES = 4;
    private static final double CELL_CLIP = 1e6;

    private final int timesteps;
    private final int featuresPerStep;
    private final int hidden;

    private final float[] encWx; // 4H × F
    private final float[] encWh; // 4H × H
    private final float[] encB;  // 4H
    private final float[] decWx; // 4H × F
    private final float[] decWh; // 4H × H
    private final float[] decB;  // 4H
    private final float[] outW;  // F × H
    private final float[] outB;  // F

    private LstmAutoencoderModel(int timesteps, int featuresPerStep, int hidden,
                                 float[] encWx, float[] encWh, float[] encB,
                                 float[] decWx, float[] decWh, float[] decB,
                                 float[] outW, float[] outB) {
        this.timesteps = timesteps;
        this.featuresPerStep = featuresPerStep;
        this.hidden = hidden;
        this.encWx = encWx;
        this.encWh = encWh;
        this.encB = encB;
        this.decWx = decWx;
        this.decWh = decWh;
        this.decB = decB;
        this.outW = outW;
        this.outB = outB;
    }

    /** 读取载荷中的序列形状 {@code [timesteps, featuresPerStep]}，供容器按 modelType 分发使用。 */
    public static int[] peekShape(byte[] weights) {
        if (weights == null || weights.length < 16) throw new IllegalArgumentException("LSTM 权重数据过短");
        for (int i = 0; i < 4; i++) {
            if (weights[i] != MAGIC[i]) throw new IllegalArgumentException("LSTM 权重魔数非法");
        }
        ByteBuffer bb = ByteBuffer.wrap(weights).order(ByteOrder.BIG_ENDIAN);
        bb.position(4);
        return new int[]{bb.getInt(), bb.getInt()};
    }

    /** 反序列化；{@code timesteps}/{@code featuresPerStep} 须与载荷内嵌形状一致。 */
    public static LstmAutoencoderModel deserialize(byte[] weights, int timesteps, int featuresPerStep) {
        if (weights == null || weights.length < 16) throw new IllegalArgumentException("LSTM 权重数据过短");
        for (int i = 0; i < 4; i++) {
            if (weights[i] != MAGIC[i]) throw new IllegalArgumentException("LSTM 权重魔数非法");
        }
        ByteBuffer bb = ByteBuffer.wrap(weights).order(ByteOrder.BIG_ENDIAN);
        bb.position(4);
        int t = bb.getInt();
        int f = bb.getInt();
        int h = bb.getInt();
        if (t != timesteps || f != featuresPerStep) {
            throw new IllegalArgumentException("LSTM 形状不匹配: " + t + "x" + f + " != " + timesteps + "x" + featuresPerStep);
        }
        if (t <= 0 || f <= 0 || h <= 0 || h > 1 << 12) throw new IllegalArgumentException("LSTM 形状非法");
        int g = GATES * h;
        int need = 16 + ((g * f + g * h + g) * 2 + (f * h + f)) * 4;
        if (weights.length != need) throw new IllegalArgumentException("LSTM 权重数据长度不一致");
        float[] encWx = get(bb, g * f);
        float[] encWh = get(bb, g * h);
        float[] encB = get(bb, g);
        float[] decWx = get(bb, g * f);
        float[] decWh = get(bb, g * h);
        float[] decB = get(bb, g);
        float[] outW = get(bb, f * h);
        float[] outB = get(bb, f);
        return new LstmAutoencoderModel(t, f, h, encWx, encWh, encB, decWx, decWh, decB, outW, outB);
    }

    /** 序列化（见类注释布局）。 */
    public byte[] serialize() {
        int cap = 16 + (encWx.length + encWh.length + encB.length
                + decWx.length + decWh.length + decB.length + outW.length + outB.length) * 4;
        ByteBuffer bb = ByteBuffer.allocate(cap).order(ByteOrder.BIG_ENDIAN);
        bb.put(MAGIC);
        bb.putInt(timesteps);
        bb.putInt(featuresPerStep);
        bb.putInt(hidden);
        put(bb, encWx);
        put(bb, encWh);
        put(bb, encB);
        put(bb, decWx);
        put(bb, decWh);
        put(bb, decB);
        put(bb, outW);
        put(bb, outB);
        return bb.array();
    }

    /**
     * 重构扁平序列；入参长度不足补零、超长截断，输出长度恒为 {@code timesteps*featuresPerStep}。
     */
    public double[] reconstruct(double[] flattenedSequence) {
        int len = timesteps * featuresPerStep;
        double[] seq = new double[len];
        if (flattenedSequence != null) {
            System.arraycopy(flattenedSequence, 0, seq, 0, Math.min(len, flattenedSequence.length));
        }
        double[] h = new double[hidden];
        double[] c = new double[hidden];
        int f = featuresPerStep;
        // 编码：逐时刻推进，末态 (h,c) 即上下文
        for (int t = 0; t < timesteps; t++) {
            step(seq, t * f, h, c, encWx, encWh, encB);
        }
        // 解码：自回归，首步零输入
        double[] out = new double[len];
        double[] prev = new double[f];
        for (int t = 0; t < timesteps; t++) {
            step(prev, 0, h, c, decWx, decWh, decB);
            int base = t * f;
            for (int j = 0; j < f; j++) {
                double y = outB[j];
                int wbase = j * hidden;
                for (int k = 0; k < hidden; k++) y += outW[wbase + k] * h[k];
                out[base + j] = y;
                prev[j] = y;
            }
        }
        return out;
    }

    /** 异常分：输入与重构的均方误差。 */
    public double anomalyScore(double[] flattenedSequence) {
        double[] rec = reconstruct(flattenedSequence);
        int len = timesteps * featuresPerStep;
        double s = 0;
        for (int i = 0; i < len; i++) {
            double v = flattenedSequence != null && i < flattenedSequence.length ? flattenedSequence[i] : 0.0;
            double d = v - rec[i];
            s += d * d;
        }
        return s / len;
    }

    /** 单时刻 LSTM 推进：就地更新 h、c。 */
    private void step(double[] src, int off, double[] h, double[] c, float[] wx, float[] wh, float[] b) {
        int H = hidden;
        int g4 = GATES * H;
        int f = featuresPerStep;
        double[] z = new double[g4];
        for (int k = 0; k < g4; k++) {
            double s = b[k];
            int xbase = k * f;
            for (int j = 0; j < f; j++) s += wx[xbase + j] * src[off + j];
            int hbase = k * H;
            for (int j = 0; j < H; j++) s += wh[hbase + j] * h[j];
            z[k] = s;
        }
        double[] hn = new double[H];
        double[] cn = new double[H];
        for (int j = 0; j < H; j++) {
            double i = sigmoid(z[j]);
            double fg = sigmoid(z[H + j]);
            double o = sigmoid(z[2 * H + j]);
            double gg = Math.tanh(clip(z[3 * H + j], -30, 30));
            double cj = clip(fg * c[j] + i * gg, -CELL_CLIP, CELL_CLIP);
            cn[j] = cj;
            hn[j] = o * Math.tanh(clip(cj, -30, 30));
        }
        System.arraycopy(hn, 0, h, 0, H);
        System.arraycopy(cn, 0, c, 0, H);
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-clip(z, -60, 60)));
    }

    private static double clip(double z, double lo, double hi) {
        return Math.max(lo, Math.min(hi, z));
    }

    private static void put(ByteBuffer bb, float[] v) {
        for (float x : v) bb.putFloat(x);
    }

    private static float[] get(ByteBuffer bb, int len) {
        float[] v = new float[len];
        for (int i = 0; i < len; i++) v[i] = bb.getFloat();
        return v;
    }

    public int timesteps() {
        return timesteps;
    }

    public int featuresPerStep() {
        return featuresPerStep;
    }
}