package com.potatotv.pacc.domain.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * §4.2.3 计费隔离：租户用量计量流水（按玩家数 / 检测量 / 存储计费）。
 * <p>每次写入租户数据时追加一条计量行，计费侧按 metric 汇总 quantity × unitPrice。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_tenant_usage")
public class TenantUsage {

    /** PLAYER / DETECTION / STORAGE。 */
    public static final String METRIC_PLAYER = "PLAYER";
    public static final String METRIC_DETECTION = "DETECTION";
    public static final String METRIC_STORAGE = "STORAGE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 32)
    private String metric;

    @Builder.Default
    @Column(nullable = false)
    private long quantity = 0L;

    @Builder.Default
    @Column(name = "unit_price", nullable = false)
    private double unitPrice = 0.0;

    @Builder.Default
    @Column(nullable = false)
    private double amount = 0.0;

    @Builder.Default
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();
}