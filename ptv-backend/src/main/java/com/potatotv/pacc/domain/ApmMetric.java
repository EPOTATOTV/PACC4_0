package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v5.4 §2.2 APM 原始采样（客户端批量上报的逐点指标）。
 *
 * <p>这是整条 APM 链路唯一的写入口：客户端每 N 秒攒一批指标，服务端逐点落库，
 * 再由 {@code ApmAggregationService} 每小时压成 {@link ApmMetricHourly} 并清理超过保留期的原始行。
 * 保留原始行的理由是「小时聚合只能回答趋势，回答不了个案」：某个玩家的 p99 抖动必须能回查原始采样。</p>
 *
 * <p>{@code metricType} 存 String 而不是 {@link MetricType} 枚举：端侧版本可能上报未来的类型名，
 * 用 {@code @Enumerated} 会把对方的兼容性问题变成我们这边 500，因此只在服务层解析并归一，
 * 未知类型一律按 gauge 落库。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_apm_metric", indexes = {
        @Index(name = "idx_apm_pteid_time", columnList = "pteid, metric_time"),
        @Index(name = "idx_apm_name_time", columnList = "metric_name, metric_time"),
        @Index(name = "idx_apm_platform_time", columnList = "platform, metric_time")
})
public class ApmMetric {

    /** 指标类型（仅供目录/查询语义使用，落库仍是字符串）。 */
    public enum MetricType {
        GAUGE,     // 瞬时值
        COUNTER,   // 累计值
        HISTOGRAM  // 直方图/分位数
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Builder.Default
    @Column(name = "pteid", nullable = false, length = 64)
    private String pteid = "";

    @Builder.Default
    @Column(name = "platform", nullable = false, length = 16)
    private String platform = "";

    @Builder.Default
    @Column(name = "client_ver", nullable = false, length = 32)
    private String clientVer = "";

    @Builder.Default
    @Column(name = "metric_time", nullable = false)
    private Instant metricTime = Instant.now();

    @Builder.Default
    @Column(name = "metric_name", nullable = false, length = 64)
    private String metricName = "";

    @Builder.Default
    @Column(name = "metric_value", nullable = false)
    private double metricValue = 0;

    @Builder.Default
    @Column(name = "metric_type", nullable = false, length = 16)
    private String metricType = MetricType.GAUGE.name();

    /** 维度标签（JSON 字符串，如 {@code {"game_version":"1.20"}}），由服务端截断到 512 字符。 */
    @Lob
    @Column(name = "tags", length = Integer.MAX_VALUE)
    private String tags;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}