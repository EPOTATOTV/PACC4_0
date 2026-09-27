package com.potatotv.pacc.service.detection.df.multimodal;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DF §4.1.3 早期融合：各模态评分（按配置权重缩放后）拼接为一个特征向量，统一输入单个判别模型。
 *
 * <p>判别模型为固定系数的 logistic，输入含每个模态的加权分与两个交互项（输入×内存、行为×图像）。
 * 交互项刻画「多模态同时异常」的相互印证：单模态高分的提升有限，两两共发会显著抬高融合分。</p>
 *
 * <p>缺失模态的维度直接记为 0 并从权重归一化中剔除，因此缺失不会把总分整体拉低。</p>
 */
@Component
public class EarlyFusionStrategy implements FusionStrategy {

    /** 判别模型截距。 */
    private static final double BIAS = -2.0;
    /** 各模态加权分的系数。 */
    private static final Map<Modality, Double> COEFFICIENTS = Map.of(
            Modality.INPUT, 3.2,
            Modality.MEMORY, 3.0,
            Modality.BEHAVIOR, 2.8,
            Modality.NETWORK, 2.0,
            Modality.IMAGE, 2.2);
    /** 交互项系数：输入×内存（外挂输入常与内存改写共生）。 */
    private static final double INTERACTION_INPUT_MEMORY = 2.0;
    /** 交互项系数：行为×图像（透视/自瞄在行为与界面上同时留痕）。 */
    private static final double INTERACTION_BEHAVIOR_IMAGE = 1.5;

    @Override
    public String name() {
        return "early";
    }

    @Override
    public FusedResult fuse(List<ModalityScore> scores, Map<Modality, Double> weights) {
        Map<Modality, Double> used = FusedResult.renormalize(weights, scores);
        if (used.isEmpty()) {
            return new FusedResult(name(), 0.0, FusedResult.verdictOf(0.0), Map.of(), Map.of(),
                    scores, FusedResult.missing(scores));
        }

        double z = BIAS;
        double scaledInput = 0;
        double scaledMemory = 0;
        double scaledBehavior = 0;
        double scaledImage = 0;
        for (ModalityScore s : scores) {
            Double w = used.get(s.modality());
            if (w == null) {
                continue;
            }
            double scaled = w * s.score();
            z += COEFFICIENTS.getOrDefault(s.modality(), 0.0) * scaled;
            switch (s.modality()) {
                case INPUT -> scaledInput = scaled;
                case MEMORY -> scaledMemory = scaled;
                case BEHAVIOR -> scaledBehavior = scaled;
                case IMAGE -> scaledImage = scaled;
                default -> {
                    // 网络模态无交互项
                }
            }
        }
        z += INTERACTION_INPUT_MEMORY * scaledInput * scaledMemory;
        z += INTERACTION_BEHAVIOR_IMAGE * scaledBehavior * scaledImage;

        double fused = FusedResult.round(sigmoid(z));
        return new FusedResult(name(), fused, FusedResult.verdictOf(fused), used,
                FusedResult.contributions(scores, used), scores, FusedResult.missing(scores));
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }
}