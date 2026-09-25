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
 * Scaffold（搭路）规则（文档 §3.2）：放置频率 + 潜行一致性 + 视角与方块面夹角。
 *
 * <p>人类搭路会持续潜行以避免掉落（sneak 一致性接近 1）且每秒放置数有限；
 * 作弊搭路在高速前进中成桥，几乎不潜行，视角也常锁定为固定俯角。</p>
 */
public final class ScaffoldRule implements CheatRule {

    /** 低于该放置速率不判定（避免把零星放置误判为搭路）。 */
    private static final double MIN_PLACE_PER_SEC = 1.0;
    /** 高速搭路阈值（文档：每秒放置方块数偏高）。 */
    private static final double FAST_PLACE_PER_SEC = 3.0;
    /** 极高搭路阈值。 */
    private static final double FASTEST_PLACE_PER_SEC = 6.0;
    /** 潜行一致性下限。 */
    private static final double MIN_SNEAK_CONSISTENCY = 0.6;
    /** 俯角锁定阈值（度）。 */
    private static final double STEEP_PITCH_DEG = 75.0;

    @Override
    public CheatType type() {
        return CheatType.SCAFFOLD;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double perSec = fv.get("feature_scaffold_block_per_sec");
        if (perSec < MIN_PLACE_PER_SEC) return Optional.empty();

        int score = 0;
        List<String> evidence = new ArrayList<>();
        if (perSec >= FAST_PLACE_PER_SEC) {
            score += 35;
            evidence.add("feature_scaffold_block_per_sec");
        }
        if (perSec >= FASTEST_PLACE_PER_SEC) score += 15;
        if (fv.get("feature_scaffold_sneak_consistency") < MIN_SNEAK_CONSISTENCY) {
            score += 30;
            evidence.add("feature_scaffold_sneak_consistency");
        }
        if (fv.get("feature_scaffold_pitch_angle") >= STEEP_PITCH_DEG) {
            score += 20;
            evidence.add("feature_scaffold_pitch_angle");
        }
        if (score < CheatFinding.REPORT_THRESHOLD) return Optional.empty();
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
