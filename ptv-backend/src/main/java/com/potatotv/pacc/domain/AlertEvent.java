package com.potatotv.pacc.domain;

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
 * 告警事件：由 {@code AlertService#evaluateRules} 依据规则触发并持久化。
 * 状态机 FIRING -> ACKNOWLEDGED -> RESOLVED；指标回落自动 RESOLVED。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_alert_event")
public class AlertEvent {

    @Id
    private String id;

    @Column(name = "rule_id")
    private String ruleId;

    @Column(name = "rule_name", nullable = false)
    private String ruleName;

    @Builder.Default
    @Column(nullable = false)
    private int severity = 1;

    @Column
    private String metric;

    @Column(name = "condition_value")
    private String conditionValue;

    @Column
    private Integer threshold;

    @Column(name = "actual_value")
    private Float actualValue;

    /** FIRING / ACKNOWLEDGED / RESOLVED。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "FIRING";

    @Column(name = "fired_at", nullable = false)
    private Instant firedAt;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolution_note", length = 4000)
    private String resolutionNote;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}