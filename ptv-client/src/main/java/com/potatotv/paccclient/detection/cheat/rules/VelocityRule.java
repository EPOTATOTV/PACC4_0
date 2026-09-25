package com.potatotv.paccclient.detection.cheat.rules;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatRule;
import com.potatotv.paccclient.detection.cheat.CheatType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Velocity（击退抑制）规则（文档 §3.2）：受击后速度变化 + 击退减少比例。
 *
 * <p>原版受击后水平速度会按固定比例衰减（约 0.6），Velocity 作弊把衰减比例压到接近 0，
 * 表现为「被击退几乎不动」。仅在确有一次受击（水平击退特征非零）时才判定。</p>
 */
public final class VelocityRule implements CheatRule {

    /** 正常击退比例下限。 */
    private static final double NORMAL_REDUCTION = 0.6;
    /** 明显抑制的击退比例。 */
    private static final double STRONG_REDUCTION = 0.3;
    /** 近乎完全免疫的击退比例。 */
    private static final double FULL_REDUCTION = 0.1;

    @Override
    public CheatType type() {
        return CheatType.VELOCITY;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        if (fv.get("feature_velocity_horizontal") <= 0) return Optional.empty();

        double reduction = fv.get("feature_velocity_reduction_ratio");
        if (reduction >= NORMAL_REDUCTION) return Optional.empty();

        int score = 45;
        List<String> evidence = new ArrayList<>();
        evidence.add("feature_velocity_reduction_ratio");
        if (reduction < STRONG_REDUCTION) score += 25;
        if (reduction < FULL_REDUCTION) {
            score += 15;
            evidence.add("feature_velocity_vertical");
        }
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
