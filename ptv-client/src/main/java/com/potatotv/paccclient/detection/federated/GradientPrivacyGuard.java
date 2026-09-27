package com.potatotv.paccclient.detection.federated;

/**
 * DF §4.1.2 端侧隐私护栏：上传前对梯度做「有限性校验 + 范数裁剪 + 样本数门限」三重把关。
 *
 * <ul>
 *   <li><b>拒绝非有限值</b>：含 NaN / ±Inf 的梯度直接拒绝（既可能是数值发散，也可能是被篡改的载荷）；</li>
 *   <li><b>范数裁剪</b>：{@link #clipNorm(double[], double)} 把 L2 范数压到配置上限，限制单设备对
 *       全局模型的单点影响力（与云端离群拒绝线对应，属标准差分隐私的前置步骤）；</li>
 *   <li><b>样本数门限</b>：本地样本过少时不参与本轮，避免小样本噪声主导聚合。</li>
 * </ul>
 *
 * <p>本类只做纯计算，不做 IO，可独立单测。</p>
 */
public final class GradientPrivacyGuard {

    private final double maxNorm;
    private final int minSamples;

    /**
     * @param maxNorm    梯度 L2 范数上限（与云端 {@code max-gradient-norm} 对应）
     * @param minSamples 允许上传所需的最小本地样本数
     */
    public GradientPrivacyGuard(double maxNorm, int minSamples) {
        this.maxNorm = maxNorm <= 0 ? Double.MAX_VALUE : maxNorm;
        this.minSamples = Math.max(1, minSamples);
    }

    /** 校验结论：{@code allowed=false} 时 {@code reason} 非空，{@code norm} 为实测范数。 */
    public record GuardResult(boolean allowed, String reason, double norm) {
    }

    /** 上传前校验（不含裁剪；裁剪在通过校验后由 {@link #clipNorm} 执行）。 */
    public GuardResult inspect(double[] gradient, int sampleCount) {
        if (gradient == null || gradient.length == 0) {
            return new GuardResult(false, "梯度为空", 0.0);
        }
        if (!GradientCodec.isFinite(gradient)) {
            return new GuardResult(false, "梯度含非有限值（NaN/Inf）", Double.NaN);
        }
        if (sampleCount < minSamples) {
            return new GuardResult(false, "本地样本数 " + sampleCount + " 少于门限 " + minSamples, GradientCodec.l2Norm(gradient));
        }
        return new GuardResult(true, "", GradientCodec.l2Norm(gradient));
    }

    /** 范数上限。 */
    public double maxNorm() {
        return maxNorm;
    }

    /** 样本数门限。 */
    public int minSamples() {
        return minSamples;
    }

    /**
     * 把梯度 L2 范数裁剪到上限以内（超限时整体等比缩放，方向不变）。
     * <p>按最大分量缩放计算，避免极端量级下范数上溢；非有限值原样返回，交由 {@link #inspect} 拒绝。</p>
     *
     * @return 裁剪后的新数组（不修改入参）
     */
    public static double[] clipNorm(double[] gradient, double maxNorm) {
        if (gradient == null || gradient.length == 0) {
            return new double[0];
        }
        double[] out = gradient.clone();
        if (!GradientCodec.isFinite(out) || maxNorm <= 0) {
            return out;
        }
        double norm = GradientCodec.l2Norm(out);
        if (norm <= maxNorm) {
            return out;
        }
        double scale = maxNorm / norm;
        for (int i = 0; i < out.length; i++) {
            out[i] *= scale;
        }
        return out;
    }
}