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
 * FastEat（快速进食）规则（文档 §3.2）：进食动画时长 + 进食间隔。
 *
 * <p>原版进食动画约 1600ms 且不可跳过；FastEat 压缩动画时长，进食间隔也呈机械规整（CV≈0）。</p>
 */
public final class FastEatRule implements CheatRule {

    /** 原版进食时长（ms）。 */
    private static final double VANILLA_DURATION_MS = 1400.0;
    /** 明显压缩的进食时长（ms）。 */
    private static final double FAST_DURATION_MS = 800.0;
    /** 间隔波动下限，低于该值视为机械节拍。 */
    private static final double MIN_INTERVAL_CV = 0.15;

    @Override
    public CheatType type() {
        return CheatType.FASTEAT;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double duration = fv.get("feature_fasteat_duration_mean");
        if (duration <= 0 || duration >= VANILLA_DURATION_MS) return Optional.empty();

        int score = (int) Math.min(75, 45 + (VANILLA_DURATION_MS - duration) / 100.0);
        List<String> evidence = new ArrayList<>();
        evidence.add("feature_fasteat_duration_mean");
        if (duration < FAST_DURATION_MS) score += 20;
        double intervalCv = fv.get("feature_fasteat_interval_cv");
        if (intervalCv > 0 && intervalCv < MIN_INTERVAL_CV) {
            score += 20;
            evidence.add("feature_fasteat_interval_cv");
        }
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
