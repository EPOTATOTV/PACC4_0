package com.potatotv.paccclient.detection.cheat.rules;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatRule;
import com.potatotv.paccclient.detection.cheat.CheatType;

import java.util.Optional;

/**
 * InvManager（背包管理）规则（文档 §3.2）：背包操作速度 + 排序模式。
 *
 * <p>InvManager 按固定模式自动整理背包（丢弃、排序、堆叠），每秒操作数远高于人工拖拽。</p>
 */
public final class InvManagerRule implements CheatRule {

    /** 人工背包操作速率上限（次/秒）。 */
    private static final double HUMAN_OPS_PER_SEC = 8.0;
    /** 每个超出单位的加分。 */
    private static final double SCORE_PER_UNIT = 4.0;

    @Override
    public CheatType type() {
        return CheatType.INVMANAGER;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double perSec = fv.get("feature_invmanager_ops_per_sec");
        if (perSec <= HUMAN_OPS_PER_SEC) return Optional.empty();
        int score = (int) Math.min(85, 40 + (perSec - HUMAN_OPS_PER_SEC) * SCORE_PER_UNIT);
        return Optional.of(CheatFinding.of(type(), score, "feature_invmanager_ops_per_sec"));
    }
}
