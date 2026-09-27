package com.potatotv.paccclient.detection;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * v5.2 端侧 PCA 降维器（文档 §2.2.1：178 维 → 64 维后上报）。
 *
 * <p>纯 JDK 实现：中心化 → 协方差矩阵 → 对称 Jacobi 特征分解 → 按特征值降序取前 k 个主成分。
 * 分解过程确定（列循环 Jacobi、特征值降序 + 下标稳定排序、主成分符号归一化），
 * 相同输入必然产生相同投影，便于灰度对比与回归测试。</p>
 *
 * <p>退化输入保护：样本数少于主成分数时自动把 k 收缩到 {@code min(components, dim, max(1, 样本数))}；
 * 零方差维度对应特征值为 0，其主成分退化为标准基，投影恒为 0。</p>
 */
public final class PcaProjector {

    private static final byte[] MAGIC = {'P', 'C', 'A', '1'};
    /** Jacobi 最大扫描轮数，178 维通常 6~10 轮即收敛。 */
    private static final int MAX_SWEEPS = 60;
    private static final double EPS = 1e-12;

    private final double[] mean;
    private final double[][] components;
    private final double[] eigenvalues;

    private PcaProjector(double[] mean, double[][] components, double[] eigenvalues) {
        this.mean = mean;
        this.components = components;
        this.eigenvalues = eigenvalues;
    }

    /**
     * 拟合 PCA。
     *
     * @param samples    样本矩阵（每行一个样本，列数一致），至少 1 行
     * @param components 期望主成分数
     */
    public static PcaProjector fit(double[][] samples, int components) {
        if (samples == null || samples.length == 0) throw new IllegalArgumentException("样本不能为空");
        int dim = samples[0].length;
        if (dim == 0) throw new IllegalArgumentException("样本维度不能为 0");
        for (double[] row : samples) {
            if (row == null || row.length != dim) throw new IllegalArgumentException("样本列数不一致");
        }
        int k = Math.max(1, Math.min(Math.min(components, dim), samples.length));

        double[] mean = new double[dim];
        for (double[] row : samples) {
            for (int j = 0; j < dim; j++) mean[j] += row[j];
        }
        for (int j = 0; j < dim; j++) mean[j] /= samples.length;

        double[][] cov = new double[dim][dim];
        double[] centered = new double[dim];
        for (double[] row : samples) {
            for (int j = 0; j < dim; j++) centered[j] = row[j] - mean[j];
            for (int a = 0; a < dim; a++) {
                if (centered[a] == 0.0) continue;
                for (int b = a; b < dim; b++) cov[a][b] += centered[a] * centered[b];
            }
        }
        double denom = samples.length > 1 ? samples.length - 1 : 1;
        for (int a = 0; a < dim; a++) {
            for (int b = a; b < dim; b++) {
                double v = cov[a][b] / denom;
                cov[a][b] = v;
                cov[b][a] = v;
            }
        }

        double[][] vectors = new double[dim][dim];
        for (int i = 0; i < dim; i++) vectors[i][i] = 1.0;
        double[] values = jacobi(cov, vectors, dim);

        Integer[] order = new Integer[dim];
        for (int i = 0; i < dim; i++) order[i] = i;
        Arrays.sort(order, (x, y) -> Double.compare(values[y], values[x]));

        double[][] comps = new double[k][dim];
        double[] eigen = new double[k];
        for (int i = 0; i < k; i++) {
            int src = order[i];
            eigen[i] = Math.max(0.0, values[src]);
            double maxAbs = 0.0;
            int pivot = 0;
            for (int j = 0; j < dim; j++) {
                double abs = Math.abs(vectors[j][src]);
                if (abs > maxAbs) {
                    maxAbs = abs;
                    pivot = j;
                }
            }
            // 符号归一化：主向量首个最大幅值分量取正，保证可复现
            double sign = vectors[pivot][src] < 0 ? -1.0 : 1.0;
            for (int j = 0; j < dim; j++) comps[i][j] = sign * vectors[j][src];
        }
        return new PcaProjector(mean, comps, eigen);
    }

    /** 投影到主成分子空间，输出长度等于主成分数。 */
    public double[] project(double[] x) {
        int dim = mean.length;
        double[] out = new double[components.length];
        for (int i = 0; i < components.length; i++) {
            double s = 0.0;
            double[] c = components[i];
            for (int j = 0; j < dim; j++) {
                s += c[j] * (j < x.length ? x[j] - mean[j] : -mean[j]);
            }
            out[i] = s;
        }
        return out;
    }

    /** 按 schema 顺序投影特征向量。 */
    public double[] project(FeatureVector fv) {
        return project(fv == null ? new double[mean.length] : fv.toArray());
    }

    /** 主成分数。 */
    public int components() {
        return components.length;
    }

    /** 输入维度。 */
    public int inputDim() {
        return mean.length;
    }

    /** 各主成分对应的特征值（降序）。 */
    public double[] eigenvalues() {
        return eigenvalues.clone();
    }

    /** 自描述二进制序列化（大端序）：[4B magic][4B inputDim][4B k][mean d][eigen k][components k*d]，均为 float64。 */
    public byte[] serialize() {
        int d = mean.length;
        int k = components.length;
        ByteBuffer bb = ByteBuffer.allocate(12 + (d + k + k * d) * 8).order(ByteOrder.BIG_ENDIAN);
        bb.put(MAGIC);
        bb.putInt(d);
        bb.putInt(k);
        for (double v : mean) bb.putDouble(v);
        for (double v : eigenvalues) bb.putDouble(v);
        for (double[] c : components) {
            for (double v : c) bb.putDouble(v);
        }
        return bb.array();
    }

    /** 反序列化；magic 或长度不合法时抛出。 */
    public static PcaProjector deserialize(byte[] raw) {
        if (raw == null || raw.length < 12) throw new IllegalArgumentException("PCA 数据过短");
        for (int i = 0; i < 4; i++) {
            if (raw[i] != MAGIC[i]) throw new IllegalArgumentException("PCA 魔数非法");
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        bb.position(4);
        int d = bb.getInt();
        int k = bb.getInt();
        if (d <= 0 || k <= 0 || k > d) throw new IllegalArgumentException("PCA 形状非法: " + d + "x" + k);
        if (raw.length != 12 + (d + k + k * d) * 8) throw new IllegalArgumentException("PCA 数据长度不一致");
        double[] mean = new double[d];
        for (int i = 0; i < d; i++) mean[i] = bb.getDouble();
        double[] eigen = new double[k];
        for (int i = 0; i < k; i++) eigen[i] = bb.getDouble();
        double[][] comps = new double[k][d];
        for (int i = 0; i < k; i++) {
            for (int j = 0; j < d; j++) comps[i][j] = bb.getDouble();
        }
        return new PcaProjector(mean, comps, eigen);
    }

    /**
     * 对称 Jacobi 特征分解：原地把 {@code a} 对角化，{@code vec} 累积特征向量（列为主向量），
     * 返回特征值数组。样例矩阵规模小（≤178），循环 Jacobi 稳定且无需第三方库。
     */
    private static double[] jacobi(double[][] a, double[][] vec, int n) {
        for (int sweep = 0; sweep < MAX_SWEEPS; sweep++) {
            double off = 0.0;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) off += a[p][q] * a[p][q];
            }
            if (off < EPS) break;
            for (int p = 0; p < n; p++) {
                for (int q = p + 1; q < n; q++) {
                    if (Math.abs(a[p][q]) < EPS) continue;
                    double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                    if (theta == 0.0) t = 1.0;
                    double c = 1.0 / Math.sqrt(t * t + 1.0);
                    double s = t * c;
                    for (int i = 0; i < n; i++) {
                        double aip = a[i][p];
                        double aiq = a[i][q];
                        a[i][p] = c * aip - s * aiq;
                        a[i][q] = s * aip + c * aiq;
                    }
                    for (int i = 0; i < n; i++) {
                        double api = a[p][i];
                        double aqi = a[q][i];
                        a[p][i] = c * api - s * aqi;
                        a[q][i] = s * api + c * aqi;
                    }
                    for (int i = 0; i < n; i++) {
                        double vip = vec[i][p];
                        double viq = vec[i][q];
                        vec[i][p] = c * vip - s * viq;
                        vec[i][q] = s * vip + c * viq;
                    }
                }
            }
        }
        double[] values = new double[n];
        for (int i = 0; i < n; i++) values[i] = a[i][i];
        return values;
    }
}