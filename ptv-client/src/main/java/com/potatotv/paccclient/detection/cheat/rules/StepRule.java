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
 * Step（自动上台阶）规则（文档 §3.2）：自动上台阶高度 + 跳跃动画。
 *
 * <p>原版无跳跃时单次净上升上限为 0.6 格（半砖/台阶）；超过该值即为 Step 作弊抬高。
 * 记录到的「上升 &gt;0.6 格且有持续位移」次数提供时序证据。</p>
 */
public final class StepRule implements CheatRule {

    /** 原版台阶高度上限（格）。 */
    private static final double VANILLA_STEP = 0.6;
    /** 明显抬高阈值（格）。 */
    private static final double TALL_STEP = 0.7;
    /** 远超原版限制的抬升高度（格）。 */
    private static final double HUGE_STEP = 1.0;

    @Override
    public CheatType type() {
        return CheatType.STEP;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double heightMax = fv.get("feature_step_height_max");
        double violations = fv.get("feature_step_violations");
        if (heightMax <= VANILLA_STEP && violations <= 0) return Optional.empty();

        int score = 0;
        List<String> evidence = new ArrayList<>();
        if (heightMax >= HUGE_STEP) {
            score += 50;
            evidence.add("feature_step_height_max");
        } else if (heightMax > TALL_STEP) {
            score += 35;
            evidence.add("feature_step_height_max");
        }
        if (violations > 0) {
            score += 25;
            evidence.add("feature_step_violations");
        }
        if (score < CheatFinding.REPORT_THRESHOLD) return Optional.empty();
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
