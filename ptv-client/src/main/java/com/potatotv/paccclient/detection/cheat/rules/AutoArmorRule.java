package com.potatotv.paccclient.detection.cheat.rules;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatRule;
import com.potatotv.paccclient.detection.cheat.CheatType;

import java.util.Optional;

/**
 * AutoArmor（自动护甲）规则（文档 §3.2）：护甲穿戴时机 + 穿戴速度。
 *
 * <p>人工换甲（两次拖拽或 shift 点击）耗时通常在 400ms 以上，且常伴随失误；
 * AutoArmor 在护甲破损的同一 tick 完成替换，平均耗时低于 250ms。</p>
 */
public final class AutoArmorRule implements CheatRule {

    /** 人类穿戴耗时下限（ms），低于该值即异常。 */
    private static final double HUMAN_MIN_MS = 400.0;
    /** 极速穿戴阈值（ms）。 */
    private static final double VERY_FAST_MS = 100.0;
    /** 快速穿戴阈值（ms）。 */
    private static final double FAST_MS = 250.0;

    @Override
    public CheatType type() {
        return CheatType.AUTOARMOR;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double delayMs = fv.get("feature_autoarmor_equip_delay");
        if (delayMs <= 0 || delayMs >= HUMAN_MIN_MS) return Optional.empty();

        int score = delayMs < VERY_FAST_MS ? 70 : delayMs < FAST_MS ? 55 : 45;
        return Optional.of(CheatFinding.of(type(), score, "feature_autoarmor_equip_delay"));
    }
}
