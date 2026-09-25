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
 * Sprint（冲刺违规）规则（文档 §3.2）：冲刺与朝向匹配、OmniSprint。
 *
 * <p>原版冲刺在急转（&gt;120°）时会被取消；OmniSprint 作弊让冲刺状态在所有方向持续保持。
 * 采集侧统计「冲刺中急转仍保持冲刺」的采样数与移动中冲刺占比。</p>
 */
public final class SprintRule implements CheatRule {

    /** 单次违规的基础分。 */
    private static final int VIOLATION_BASE = 40;
    /** 单次违规的增量分。 */
    private static final int VIOLATION_STEP = 5;
    /** 移动中冲刺占比的机器化阈值（始终冲刺）。 */
    private static final double ALWAYS_SPRINT_RATIO = 0.99;

    @Override
    public CheatType type() {
        return CheatType.SPRINT;
    }

    @Override
    public Optional<CheatFinding> evaluate(FeatureVector fv, AnalysisContext ctx) {
        double violations = fv.get("feature_omnisprint_violations");
        if (violations <= 0) return Optional.empty();

        int score = (int) Math.min(65, VIOLATION_BASE + violations * VIOLATION_STEP);
        List<String> evidence = new ArrayList<>();
        evidence.add("feature_omnisprint_violations");
        if (fv.get("feature_sprint_consistency") >= ALWAYS_SPRINT_RATIO) {
            score += 12;
            evidence.add("feature_sprint_consistency");
        }
        return Optional.of(CheatFinding.of(type(), score, evidence.toArray(String[]::new)));
    }
}
