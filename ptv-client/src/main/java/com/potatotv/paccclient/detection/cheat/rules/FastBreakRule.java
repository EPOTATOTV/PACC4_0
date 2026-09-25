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
 * FastBreak（快速破坏）规则（文档 §3.2）：每秒破坏方块数 + 破坏动画完整性。
 *
 * <p>破坏速率超限说明绕过了方块硬度计算；挥臂动画速率波动极低（CV≈0）说明动画是机器节拍。</p>
 */
public final class FastBreakRule implements CheatRule {

    /** 文档判定下限（个/秒）。 */
    private static final double ABNORMAL_PER_SEC = 3.0;
    /** 明显超限的破坏速率。 */
    private static final double FAST_PER_SEC = 5.0;
    /** 挥臂动画波动下限，低于该值视为机械节拍。 */
    private static final double MIN_ANIMATION_CV = 0.1;

    @Override
    public CheatType type() {
        return CheatType.FASTBREAK;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double perSec = fv.get("feature_fastbreak_block_per_sec");
        if (perSec < ABNORMAL_PER_SEC) return Optional.empty();

        int score = 45;
        List<String> evidence = new ArrayList<>();
        evidence.add("feature_fastbreak_block_per_sec");
        if (perSec >= FAST_PER_SEC) score += 25;
        double animationCv = fv.get("feature_swing_animation_cv");
        if (animationCv > 0 && animationCv < MIN_ANIMATION_CV) {
            score += 20;
            evidence.add("feature_swing_animation_cv");
        }
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
