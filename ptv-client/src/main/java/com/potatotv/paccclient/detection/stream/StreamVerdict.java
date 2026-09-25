package com.potatotv.paccclient.detection.stream;

import java.util.List;

/**
 * DF §4.1.1 流式检测判定结果（字段与云端 {@code StreamVerdict} 对齐，便于端云对齐与丢包排查）。
 *
 * @param sequence    管线内递增序号
 * @param pteid       玩家 PTEID
 * @param eventType   事件类型
 * @param detected    是否判定为作弊/异常
 * @param tier        命中层级：{@code RULE} / {@code RULE_AI} / {@code NONE}
 * @param riskScore   融合风险分 0-1
 * @param ruleScore   快速规则层得分 0-1
 * @param aiScore     AI 精判层得分 0-1；未触发精判时为 0
 * @param aiInvoked   本事件是否进入了 AI 精判层
 * @param reasons     判定依据（人类可读）
 * @param latencyMs   端到端延迟（接收 → 判定完成，毫秒）
 */
public record StreamVerdict(
        long sequence,
        String pteid,
        String eventType,
        boolean detected,
        String tier,
        double riskScore,
        double ruleScore,
        double aiScore,
        boolean aiInvoked,
        List<String> reasons,
        double latencyMs
) {

    /** 命中层级：快速规则层。 */
    public static final String TIER_RULE = "RULE";
    /** 命中层级：规则 + AI 精判。 */
    public static final String TIER_RULE_AI = "RULE_AI";
    /** 未命中任何层级。 */
    public static final String TIER_NONE = "NONE";
}