package com.potatotv.pacc.service.detection.df.federated;

import java.util.List;

/**
 * DF §4.1.2 FedAvg 聚合器（纯函数，无 IO / 无 Spring，可独立单测）。
 *
 * <p>按客户端样本数加权平均梯度：{@code g = Σ(n_i · g_i) / Σ n_i}。样本数作权重是因为各端本地数据量
 * 差异很大，等权平均会让小数据端的噪声主导全局模型。聚合得到的梯度再以固定步长作用于全局参数
 * （{@link #applyGradient}），即标准的 Federated SGD 更新。</p>
 *
 * <p>隐私校验（{@link #validate}）在聚合前逐条执行：形状（维度）一致、分量全部有限、范数不超上限；
 * 任一不满足即拒绝该客户端（服务层据此落表留痕），保证离群/污染更新不会进入聚合。</p>
 */
public final class FedAvgAggregator {

    private FedAvgAggregator() {
    }

    /** 聚合结果：加权平均梯度 + 参与样本数 + 加权平均损失 + 参与客户端数。 */
    public record Aggregate(double[] gradient, long totalSamples, double avgLoss, int clients) {
    }

    /** 校验结论：{@code valid=false} 时 {@code reason} 非空。 */
    public record Validation(boolean valid, String reason) {

        static Validation ok() {
            return new Validation(true, "");
        }

        static Validation reject(String reason) {
            return new Validation(false, reason);
        }
    }

    /**
     * 隐私与形状校验。
     *
     * @param gradient    待校验梯度
     * @param expectedDim 期望维度；0 表示本要素尚未确定（首个合法更新确立维度）
     * @param maxDim      维度上限（防超大载荷）
     * @param maxNorm     单客户端梯度范数上限（超限视为离群，拒绝以限制单点影响力）
     */
    public static Validation validate(double[] gradient, int expectedDim, int maxDim, double maxNorm) {
        if (gradient == null || gradient.length == 0) {
            return Validation.reject("梯度为空");
        }
        if (gradient.length > maxDim) {
            return Validation.reject("梯度维度 " + gradient.length + " 超过上限 " + maxDim);
        }
        if (expectedDim > 0 && gradient.length != expectedDim) {
            return Validation.reject("梯度维度 " + gradient.length + " 与本轮约定维度 " + expectedDim + " 不一致");
        }
        if (!GradientCodec.isFinite(gradient)) {
            return Validation.reject("梯度含非有限值（NaN/Inf）");
        }
        double norm = GradientCodec.l2Norm(gradient);
        if (norm > maxNorm) {
            return Validation.reject("梯度范数 " + fmt(norm) + " 超过上限 " + fmt(maxNorm) + "（离群）");
        }
        return Validation.ok();
    }

    /**
     * 按样本数加权平均。
     *
     * @param updates 已通过校验的客户端梯度（非空）
     * @throws IllegalArgumentException 列表为空或样本数合计为 0
     */
    public static Aggregate aggregate(List<ClientGradient> updates) {
        if (updates == null || updates.isEmpty()) {
            throw new IllegalArgumentException("没有可聚合的客户端梯度");
        }
        int dim = updates.get(0).gradient().length;
        long totalSamples = 0;
        double[] acc = new double[dim];
        double lossWeighted = 0;
        double lossWeight = 0;
        for (ClientGradient u : updates) {
            if (u.gradient() == null || u.gradient().length != dim) {
                throw new IllegalArgumentException("客户端 " + u.clientId() + " 的梯度维度与基准不一致");
            }
            int n = u.sampleCount();
            if (n <= 0) {
                throw new IllegalArgumentException("客户端 " + u.clientId() + " 的样本数必须为正");
            }
            totalSamples += n;
            for (int i = 0; i < dim; i++) {
                acc[i] += u.gradient()[i] * n;
            }
            if (u.loss() != null && Double.isFinite(u.loss())) {
                lossWeighted += u.loss() * n;
                lossWeight += n;
            }
        }
        if (totalSamples == 0) {
            throw new IllegalArgumentException("参与聚合的样本数合计为 0");
        }
        for (int i = 0; i < dim; i++) {
            acc[i] /= totalSamples;
        }
        double avgLoss = lossWeight == 0 ? 0.0 : lossWeighted / lossWeight;
        return new Aggregate(acc, totalSamples, avgLoss, updates.size());
    }

    /**
     * 以聚合梯度更新全局参数：{@code w' = w - lr · g}。
     *
     * @param weights  当前全局参数；长度与梯度不一致时（维度变更）从零向量重新初始化
     * @param gradient 聚合梯度
     * @param learningRate 步长（&gt;0）
     */
    public static double[] applyGradient(double[] weights, double[] gradient, double learningRate) {
        if (gradient == null || gradient.length == 0) {
            throw new IllegalArgumentException("聚合梯度为空");
        }
        if (learningRate <= 0) {
            throw new IllegalArgumentException("学习率必须为正：" + learningRate);
        }
        double[] base = (weights != null && weights.length == gradient.length) ? weights : new double[gradient.length];
        double[] out = new double[gradient.length];
        for (int i = 0; i < gradient.length; i++) {
            out[i] = base[i] - learningRate * gradient[i];
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }
}