package com.potatotv.pacc.service.detection.df.multimodal;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DF §4.1.3 混合融合：关键模态（输入 / 内存 / 行为）走早期融合，辅助模态（网络 / 图像）走晚期融合，再合成。
 *
 * <p>动机：关键模态信号相关性高、共同异常时相互印证价值大，适合拼接后交给同一判别模型；辅助模态
 * 信号独立且噪声更大，适合各自评分后再以固定比例并入，避免污染关键模态的早期特征空间。</p>
 *
 * <p>合成比例固定为「关键 {@value #KEY_SHARE} / 辅助 {@value #AUX_SHARE}」；某一侧整体缺失时，
 * 融合分直接取另一侧结果（不补零、不报错）。</p>
 */
@Component
public class HybridFusionStrategy implements FusionStrategy {

    /** 关键模态侧占比。 */
    private static final double KEY_SHARE = 0.7;
    /** 辅助模态侧占比。 */
    private static final double AUX_SHARE = 0.3;
    /** 关键模态早期判别模型：截距与系数。 */
    private static final double KEY_BIAS = -1.6;
    private static final double KEY_COEFFICIENT = 3.0;

    @Override
    public String name() {
        return "hybrid";
    }

    @Override
    public FusedResult fuse(List<ModalityScore> scores, Map<Modality, Double> weights) {
        Map<Modality, Double> used = FusedResult.renormalize(weights, scores);
        if (used.isEmpty()) {
            return new FusedResult(name(), 0.0, FusedResult.verdictOf(0.0), Map.of(), Map.of(),
                    scores, FusedResult.missing(scores));
        }

        double keyWeightSum = 0;
        double keyScaledSum = 0;
        double auxWeightSum = 0;
        double auxLateSum = 0;
        for (ModalityScore s : scores) {
            Double w = used.get(s.modality());
            if (w == null) {
                continue;
            }
            if (s.modality().keyModality()) {
                keyWeightSum += w;
                keyScaledSum += w * s.score();
            } else {
                auxWeightSum += w;
                auxLateSum += w * s.score();
            }
        }

        double fused;
        if (keyWeightSum > 0 && auxWeightSum > 0) {
            // 关键侧早期融合（组内归一化后进 logistic）+ 辅助侧晚期加权，再按固定比例合成
            double keyEarly = sigmoid(KEY_BIAS + KEY_COEFFICIENT * (keyScaledSum / keyWeightSum));
            double auxLate = auxLateSum / auxWeightSum;
            fused = KEY_SHARE * keyEarly + AUX_SHARE * auxLate;
        } else if (keyWeightSum > 0) {
            fused = sigmoid(KEY_BIAS + KEY_COEFFICIENT * (keyScaledSum / keyWeightSum));
        } else {
            fused = auxLateSum / auxWeightSum;
        }

        double rounded = FusedResult.round(fused);
        return new FusedResult(name(), rounded, FusedResult.verdictOf(rounded), used,
                FusedResult.contributions(scores, used), scores, FusedResult.missing(scores));
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }
}