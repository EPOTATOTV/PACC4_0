package com.potatotv.paccclient.detection.cheat;

import java.util.List;

/**
 * 单条规则命中结果。
 *
 * @param type         命中的作弊类型
 * @param score        0-100 置信分
 * @param severity     严重度（{@code high}/{@code medium}/{@code low}），由分数推导
 * @param evidenceKeys 支撑本次判定的特征键（用于人工复核与模型回流）
 */
public record CheatFinding(CheatType type, int score, String severity, List<String> evidenceKeys) {

    /** 低于该分值的命中不进入上报链路。 */
    public static final int REPORT_THRESHOLD = 40;

    /**
     * 构造命中结果（分数钳制到 0-100，严重度按 70/40 分档）。
     *
     * @param type         作弊类型
     * @param score        原始分数
     * @param evidenceKeys 证据特征键
     */
    public static CheatFinding of(CheatType type, int score, String... evidenceKeys) {
        int s = Math.max(0, Math.min(100, score));
        return new CheatFinding(type, s, severityOf(s), List.of(evidenceKeys));
    }

    /** 分数到严重度的映射。 */
    public static String severityOf(int score) {
        if (score >= 70) return "high";
        if (score >= REPORT_THRESHOLD) return "medium";
        return "low";
    }

    /** 是否达到上报阈值。 */
    public boolean reportable() {
        return score >= REPORT_THRESHOLD;
    }
}
