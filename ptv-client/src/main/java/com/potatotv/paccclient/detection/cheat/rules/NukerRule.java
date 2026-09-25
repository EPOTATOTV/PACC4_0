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
 * Nuker（范围破坏）规则（文档 §3.2）：单 tick 同时破坏方块的范围分析。
 *
 * <p>原版一次只能破坏一个方块（半径为 0）；有覆盖半径即说明在批量破坏周围方块。</p>
 */
public final class NukerRule implements CheatRule {

    /** 触发判定的覆盖半径（格）。 */
    private static final double MIN_RADIUS = 2.5;
    /** 明显范围破坏半径（格）。 */
    private static final double WIDE_RADIUS = 5.0;
    /** 频繁破坏速率（个/秒）。 */
    private static final double BUSY_PER_SEC = 5.0;

    @Override
    public CheatType type() {
        return CheatType.NUKER;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double radius = fv.get("feature_nuker_break_radius");
        if (radius < MIN_RADIUS) return Optional.empty();

        int score = 45;
        List<String> evidence = new ArrayList<>();
        evidence.add("feature_nuker_break_radius");
        if (radius >= WIDE_RADIUS) score += 25;
        if (fv.get("feature_fastbreak_block_per_sec") >= BUSY_PER_SEC) {
            score += 20;
            evidence.add("feature_fastbreak_block_per_sec");
        }
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
