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
 * NoFall（无坠落伤害）规则（文档 §3.2）：落地伤害 + 虚空 Y 位置。
 *
 * <p>原版从 3 格以上坠落必然受伤；采集侧记录「超过 3 格却未受伤」的次数。
 * 虚空（Y&lt;0）长时间滞留且未受伤同样说明下落伤害被拦截。</p>
 */
public final class NoFallRule implements CheatRule {

    /** 单次无伤坠落的基础分。 */
    private static final int VIOLATION_BASE = 45;
    /** 单次无伤坠落的增量分。 */
    private static final int VIOLATION_STEP = 10;
    /** 虚空滞留加分阈值（秒）。 */
    private static final double VOID_TIME_SEC = 1.0;

    @Override
    public CheatType type() {
        return CheatType.NOFALL;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double violations = fv.get("feature_nofall_violations");
        double voidHits = fv.get("feature_nofall_void");
        if (violations <= 0 && voidHits <= 0) return Optional.empty();

        int score = 0;
        List<String> evidence = new ArrayList<>();
        if (violations > 0) {
            score += (int) Math.min(70, VIOLATION_BASE + violations * VIOLATION_STEP);
            evidence.add("feature_nofall_violations");
        }
        if (voidHits > 0) {
            score += 30;
            evidence.add("feature_nofall_void");
        }
        if (fv.get("feature_void_time") > VOID_TIME_SEC) score += 10;
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
