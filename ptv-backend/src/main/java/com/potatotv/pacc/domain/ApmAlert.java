package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * v5.4 §2.6 APM 阈值告警：小时聚合指标越过阈值时留下的一条待处理记录。
 *
 * <p>去重口径是 {@code alert_key = 规则 + 平台 + 版本}：同一个版本同一个平台上同一个规则反复越线时，
 * 只有冷却窗口外才新开一条，避免刷屏；冷却窗口由 {@code pacc.apm.alert-cooldown-minutes} 配置。
 * 因此 {@code status} 保留 OPEN / ACKED / RESOLVED 三态，ACKEED 之前的 OPEN 行即是「当前未处理」。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_apm_alert", indexes = {
        @Index(name = "idx_apm_alert_status", columnList = "status, occurred_at"),
        @Index(name = "idx_apm_alert_key", columnList = "alert_key, occurred_at")
})
public class ApmAlert {

    /** 处理状态。 */
    public enum Status {
        OPEN,      // 待处理
        ACKED,     // 已确认
        RESOLVED   // 已解决
    }

    /** 告警级别：P0 阻断体验 / P1 明显劣化 / P2 需关注。 */
    public enum Level {
        P0, P1, P2
    }

    @Id
    @Builder.Default
    @Column(name = "id", nullable = false, length = 64)
    private String id = UUID.randomUUID().toString().replace("-", "");

    /** 去重键：{@code 规则key|平台|版本}。 */
    @Builder.Default
    @Column(name = "alert_key", nullable = false, length = 128)
    private String alertKey = "";

    @Builder.Default
    @Column(name = "alert_name", nullable = false, length = 64)
    private String alertName = "";

    @Builder.Default
    @Column(name = "metric_name", nullable = false, length = 64)
    private String metricName = "";

    @Builder.Default
    @Column(name = "platform", nullable = false, length = 16)
    private String platform = "";

    @Builder.Default
    @Column(name = "client_ver", nullable = false, length = 32)
    private String clientVer = "";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "level", nullable = false, length = 4)
    private Level level = Level.P2;

    @Builder.Default
    @Column(name = "threshold", nullable = false)
    private double threshold = 0;

    /** 触发时的实测值（小时聚合的 p95）。 */
    @Builder.Default
    @Column(name = "observed", nullable = false)
    private double observed = 0;

    @Builder.Default
    @Column(name = "message", nullable = false, length = 512)
    private String message = "";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private Status status = Status.OPEN;

    @Builder.Default
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "acked_by", length = 64)
    private String ackedBy;

    @Column(name = "acked_at")
    private Instant ackedAt;
}