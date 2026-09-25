package com.potatotv.paccclient.detection.cheat.rules;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatRule;
import com.potatotv.paccclient.detection.cheat.CheatType;

import java.util.Optional;

/**
 * FastPlace（快速放置）规则（文档 §3.2）：每秒放置方块数阈值 + 分布分析。
 *
 * <p>原版放置受右手挥动冷却限制（约 4 次/秒上限，正常情况下更低）；
 * 超过 2 次/秒且随速率线性加分，速率越高越可能是 FastPlace。</p>
 */
public final class FastPlaceRule implements CheatRule {

    /** 文档给出的异常下限（次/秒）。 */
    private static final double ABNORMAL_PER_SEC = 2.0;
    /** 每个超出单位的加分。 */
    private static final double SCORE_PER_UNIT = 8.0;

    @Override
    public CheatType type() {
        return CheatType.FASTPLACE;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double perSec = fv.get("feature_fastplace_block_per_sec");
        if (perSec < ABNORMAL_PER_SEC) return Optional.empty();
        int score = (int) Math.min(85, 40 + (perSec - ABNORMAL_PER_SEC) * SCORE_PER_UNIT);
        return Optional.of(CheatFinding.of(type(), score, "feature_fastplace_block_per_sec"));
    }
}
