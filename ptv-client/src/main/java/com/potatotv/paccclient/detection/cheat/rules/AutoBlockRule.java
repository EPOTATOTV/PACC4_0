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
 * AutoBlock（自动格挡）规则（文档 §3.2）：格挡时机 + 格挡与攻击的时序。
 *
 * <p>原版在攻击后恢复格挡需要人手操作（反应时间 &gt;100ms）；AutoBlock 在同一 tick 内完成
 * 「攻击→格挡」切换，因此切换耗时接近 0 且格挡覆盖率极高。</p>
 */
public final class AutoBlockRule implements CheatRule {

    /** 格挡覆盖率判定下限。 */
    private static final double HIGH_RATIO = 0.5;
    /** 几乎每次都格挡。 */
    private static final double NEAR_FULL_RATIO = 0.9;
    /** 人类反应时间下限（ms），低于该值不可能由人手完成。 */
    private static final double MIN_HUMAN_REACTION_MS = 50.0;
    /** 时序自编码异常分对置信度的抬升阈值。 */
    private static final double TEMPORAL_BOOST_THRESHOLD = 0.5;

    @Override
    public CheatType type() {
        return CheatType.AUTOBLOCK;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double ratio = fv.get("feature_autoblock_ratio");
        if (ratio <= 0) return Optional.empty();

        int score = 0;
        List<String> evidence = new ArrayList<>();
        if (ratio >= HIGH_RATIO) {
            score += 40;
            evidence.add("feature_autoblock_ratio");
        }
        if (ratio >= NEAR_FULL_RATIO) score += 15;
        if (fv.get("feature_autoblock_switch_time") < MIN_HUMAN_REACTION_MS) {
            score += 30;
            evidence.add("feature_autoblock_switch_time");
        }
        if (ctx != null && ctx.temporalAnomaly() > TEMPORAL_BOOST_THRESHOLD) {
            score += 10;
            evidence.add("feature_attack_interval_cv");
        }
        if (score < CheatFinding.REPORT_THRESHOLD) return Optional.empty();
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
