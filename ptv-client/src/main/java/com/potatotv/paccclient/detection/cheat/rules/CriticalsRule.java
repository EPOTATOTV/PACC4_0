package com.potatotv.paccclient.detection.cheat.rules;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.analysis.ClickIntervalAnalyzer;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatRule;
import com.potatotv.paccclient.detection.cheat.CheatType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Criticals（非法暴击）规则（文档 §3.2）：暴击率 + 非跳跃暴击 + 离地占比。
 *
 * <p>原版暴击必须在下落中触发，因此「未离地却是暴击」在物理上不可能；
 * 暴击率接近 1 且离地占比偏低说明是 Criticals 作弊强制暴击。</p>
 */
public final class CriticalsRule implements CheatRule {

    /** 暴击率异常下限。 */
    private static final double HIGH_CRIT_RATE = 0.85;
    /** 暴击离地占比下限，低于该值说明暴击不依赖下落。 */
    private static final double MIN_AIRBORNE_RATIO = 0.9;
    /** 单次不可能暴击的基础分。 */
    private static final int IMPOSSIBLE_BASE = 20;
    /** 单次不可能暴击的增量分。 */
    private static final int IMPOSSIBLE_STEP = 10;

    @Override
    public CheatType type() {
        return CheatType.CRITICALS;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double impossible = fv.get("feature_criticals_impossible");
        double rate = fv.get("feature_criticals_rate");
        if (impossible <= 0 && rate <= 0) return Optional.empty();

        int score = 0;
        List<String> evidence = new ArrayList<>();
        if (impossible > 0) {
            score += (int) Math.min(50, IMPOSSIBLE_BASE + impossible * IMPOSSIBLE_STEP);
            evidence.add("feature_criticals_impossible");
        }
        if (rate >= HIGH_CRIT_RATE && fv.get("feature_criticals_airborne_ratio") < MIN_AIRBORNE_RATIO) {
            score += 35;
            evidence.add("feature_criticals_rate");
        }
        // 连点器与非法暴击常同时出现（同一作弊客户端的两个模块）
        if (ctx != null && ctx.click() != null
                && ctx.click().verdict() == ClickIntervalAnalyzer.Verdict.HIGHLY_LIKELY) {
            score += 15;
            evidence.add("feature_click_interval_cv");
        }
        if (score < CheatFinding.REPORT_THRESHOLD) return Optional.empty();
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
