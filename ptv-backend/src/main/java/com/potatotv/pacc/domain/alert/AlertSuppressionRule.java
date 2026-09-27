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
 * §4.3.2 误报抑制规则：命中 {@code pattern}（对家族码 / 规则名 / 指标做包含匹配）的告警被自动抑制，
 * 不进入聚合与通知，从而降低告警噪声。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_alert_suppression_rule")
public class AlertSuppressionRule {

    @Id
    @Column(length = 64)
    private String id;

    @Column(nullable = false, length = 128)
    private String name;

    /** 匹配模式：对 familyCode / ruleId / ruleName / metric 做不区分大小写的包含匹配。 */
    @Column(nullable = false, length = 255)
    private String pattern;

    /** 可选：限定作弊家族。 */
    @Column(name = "family_code", length = 64)
    private String familyCode;

    @Column(length = 512)
    private String reason;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(name = "hit_count", nullable = false)
    private long hitCount = 0L;

    @Column(name = "last_hit_at")
    private Instant lastHitAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}