package com.potatotv.pacc.service.detection.df.multimodal;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DF §4.1.3 晚期融合：各模态先独立评分，再按权重加权求和（权重按出现模态重新归一化）。
 *
 * <p>最简单也最稳的策略：任一模态缺席只影响自己的那一项，其余模态的相对权重按比例放大，
 * 因此「某模态本轮没有数据」不会改变其余模态的判定尺度。</p>
 */
@Component
public class LateFusionStrategy implements FusionStrategy {

    @Override
    public String name() {
        return "late";
    }

    @Override
    public FusedResult fuse(List<ModalityScore> scores, Map<Modality, Double> weights) {
        Map<Modality, Double> used = FusedResult.renormalize(weights, scores);
        if (used.isEmpty()) {
            return new FusedResult(name(), 0.0, FusedResult.verdictOf(0.0), Map.of(), Map.of(),
                    scores, FusedResult.missing(scores));
        }
        double fused = 0;
        for (ModalityScore s : scores) {
            Double w = used.get(s.modality());
            if (w != null) {
                fused += w * s.score();
            }
        }
        double rounded = FusedResult.round(fused);
        return new FusedResult(name(), rounded, FusedResult.verdictOf(rounded), used,
                FusedResult.contributions(scores, used), scores, FusedResult.missing(scores));
    }
}