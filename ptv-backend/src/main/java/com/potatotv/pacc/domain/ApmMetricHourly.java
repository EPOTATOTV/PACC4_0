package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v5.4 §2.3 APM 小时聚合：按 {@code 平台 + 客户端版本 + 小时 + 指标} 汇总原始采样的分位数。
 *
 * <p>为什么落库而不是查询时现算：原始表按 7 天滚动清理，查询时现算等于「7 天以前查不到」；
 * 而且管理端看的是 30 天趋势，逐次扫描原始表会把库拖死。小时行是长期保留的，
 * 因此这里存 {@code sample_count}（样本量）随行保留——只看 p95 不看样本量会被少量样本误导。</p>
 *
 * <p>唯一键 {@code uk_apm_hourly} 让聚合可重跑：同一小时重复聚合走 upsert（先按唯一键查再更新），
 * 补跑/重跑不会产生重复行。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_apm_metric_hourly", uniqueConstraints = {
        @UniqueConstraint(name = "uk_apm_hourly",
                columnNames = {"platform", "client_ver", "metric_hour", "metric_name"})
})
public class ApmMetricHourly {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "platform", nullable = false, length = 16)
    private String platform = "";

    @Builder.Default
    @Column(name = "client_ver", nullable = false, length = 32)
    private String clientVer = "";

    @Builder.Default
    @Column(name = "metric_hour", nullable = false)
    private Instant metricHour = Instant.now();

    @Builder.Default
    @Column(name = "metric_name", nullable = false, length = 64)
    private String metricName = "";

    /** 算术平均。 */
    @Builder.Default
    @Column(name = "avg_value", nullable = false)
    private double avgValue = 0;

    /** 中位数。 */
    @Builder.Default
    @Column(name = "p50_value", nullable = false)
    private double p50Value = 0;

    /** 95 分位（告警与回归判定的主力口径）。 */
    @Builder.Default
    @Column(name = "p95_value", nullable = false)
    private double p95Value = 0;

    /** 99 分位。 */
    @Builder.Default
    @Column(name = "p99_value", nullable = false)
    private double p99Value = 0;

    @Builder.Default
    @Column(name = "max_value", nullable = false)
    private double maxValue = 0;

    /** 参与聚合的原始样本数（截断前）。 */
    @Builder.Default
    @Column(name = "sample_count", nullable = false)
    private long sampleCount = 0L;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}