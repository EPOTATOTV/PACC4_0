package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.domain.AlertEvent;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.regex.Pattern;

/**
 * §4.3.2 原始告警信号：告警流进入降噪流水线的输入单元。
 *
 * <p>相比持久化的 {@link AlertEvent}，信号额外携带租户 / 玩家 / 作弊家族维度，
 * 作为「同一玩家合并」「同一家族聚合」的聚合键。</p>
 */
@Getter
@Builder
@AllArgsConstructor
public final class AlertSignal {

    private static final Pattern PTEID = Pattern.compile("^PT[0-9A-Za-z]{4,}$");

    private final String alertId;
    private final String tenantId;
    /** 玩家标识（红屏告警可为空）。 */
    private final String playerId;
    /** 作弊家族码。 */
    private final String familyCode;
    private final String ruleId;
    private final String ruleName;
    private final String metric;
    private final int severity;
    private final Instant occurredAt;

    /**
     * 聚合键：优先按玩家合并（同一玩家的多个告警 → 1 条），
     * 其次按作弊家族聚合（同一家族的多个告警 → 一个事件），最后退回按规则+指标聚合。
     */
    public String groupKey() {
        if (playerId != null && !playerId.isBlank()) {
            return "player:" + nvl(tenantId) + ":" + playerId;
        }
        if (familyCode != null && !familyCode.isBlank()) {
            return "family:" + nvl(tenantId) + ":" + familyCode;
        }
        return "rule:" + nvl(tenantId) + ":" + nvl(ruleId) + ":" + nvl(metric);
    }

    /** 从持久化告警事件还原信号：可用字段有限，玩家标识仅在条件值形如 PTEID 时推断。 */
    public static AlertSignal fromEvent(AlertEvent ev) {
        String cv = ev.getConditionValue();
        String player = cv != null && PTEID.matcher(cv.trim()).matches() ? cv.trim() : null;
        return AlertSignal.builder()
                .alertId(ev.getId())
                .ruleId(ev.getRuleId())
                .ruleName(ev.getRuleName())
                .metric(ev.getMetric())
                .severity(ev.getSeverity())
                .occurredAt(ev.getFiredAt() == null ? Instant.now() : ev.getFiredAt())
                .playerId(player)
                .build();
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }
}