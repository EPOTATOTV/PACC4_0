package com.potatotv.paccclient.detection.cheat.rules;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.cheat.CheatFinding;
import com.potatotv.paccclient.detection.cheat.CheatRule;
import com.potatotv.paccclient.detection.cheat.CheatType;

import java.util.Optional;

/**
 * ChestStealer（快速箱子）规则（文档 §3.2）：箱子界面操作速度 + 物品移动序列。
 *
 * <p>人工翻箱受鼠标移动与界面点击限制（约 5 件/秒上限）；ChestStealer 一个 tick 内搬空箱子，
 * 每秒取物数远超人手。</p>
 */
public final class ChestStealerRule implements CheatRule {

    /** 人类取物速率上限（件/秒）。 */
    private static final double HUMAN_ITEMS_PER_SEC = 5.0;
    /** 每个超出单位的加分。 */
    private static final double SCORE_PER_UNIT = 4.0;

    @Override
    public CheatType type() {
        return CheatType.CHESTSTEALER;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double perSec = fv.get("feature_cheststealer_items_per_sec");
        if (perSec <= HUMAN_ITEMS_PER_SEC) return Optional.empty();
        int score = (int) Math.min(85, 40 + (perSec - HUMAN_ITEMS_PER_SEC) * SCORE_PER_UNIT);
        return Optional.of(CheatFinding.of(type(), score, "feature_cheststealer_items_per_sec"));
    }
}
