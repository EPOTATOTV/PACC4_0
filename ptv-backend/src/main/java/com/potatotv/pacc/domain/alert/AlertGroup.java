package com.potatotv.pacc.domain.alert;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * §4.3.2 告警聚合组：同一玩家 / 同一作弊家族在聚合窗口内的多条原始告警合并为一条。
 * <p>{@code signalCount} 记录被合并的原始告警数，是降噪率（A23）的度量基础。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_alert_group")
public class AlertGroup {

    public static final String ST_OPEN = "OPEN";
    public static final String ST_NOTIFIED = "NOTIFIED";

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    /** 同一玩家维度聚合键（可选）。 */
    @Column(name = "player_id", length = 64)
    private String playerId;

    /** 同一作弊家族维度聚合键（可选）。 */
    @Column(name = "family_code", length = 64)
    private String familyCode;

    @Column(name = "rule_id", length = 255)
    private String ruleId;

    @Builder.Default
    @Column(nullable = false)
    private int severity = 1;

    @Builder.Default
    @Column(nullable = false, length = 8)
    private String priority = "P2";

    @Builder.Default
    @Column(name = "signal_count", nullable = false)
    private int signalCount = 1;

    /** 原始告警 id 列表（逗号分隔，截断保存）。 */
    @Column(name = "raw_alert_ids", length = 2000)
    private String rawAlertIds;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = ST_OPEN;

    @Builder.Default
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt = Instant.now();

    @Builder.Default
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt = Instant.now();

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}