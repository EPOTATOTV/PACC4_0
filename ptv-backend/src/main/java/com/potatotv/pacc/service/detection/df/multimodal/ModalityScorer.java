package com.potatotv.pacc.service.detection.df.multimodal;

import java.util.List;

/**
 * DF §4.1.3 模态评分器：把某一模态的原始输入映射为 0-1 异常分。
 *
 * <p>三种融合策略共用同一批评分器，因此「换融合方式」不改变单模态语义，只改变组合方式。</p>
 */
public interface ModalityScorer {

    /** 本评分器负责的模态。 */
    Modality modality();

    /** 期望的输入信号名（用于覆盖率与置信度计算）。 */
    List<String> signals();

    /** 评分。 */
    ModalityScore score(ModalityInput input);

    /** 置信度 = 实际出现的期望信号数 / 期望信号数。 */
    default double confidence(ModalityInput input) {
        List<String> all = signals();
        if (all.isEmpty()) {
            return 0.0;
        }
        return Math.min(1.0, input.presentCount(all) / (double) all.size());
    }

    /** 数值裁剪到 [0,1]。 */
    default double clamp01(double v) {
        return v < 0 ? 0.0 : Math.min(v, 1.0);
    }
}