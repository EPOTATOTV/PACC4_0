package com.potatotv.pacc.domain.tenant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * §4.2.3 数据隔离：租户独立的检测记录（每租户独立检测记录 / 玩家数据）。
 * <p>所有读写必须经 {@code TenantDataService} 的租户守卫，禁止跨租户访问（验收 A25）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_tenant_detection_record")
public class TenantDetectionRecord {

    @Id
    @Column(length = 64)
    @Builder.Default
    private String id = UUID.randomUUID().toString().replace("-", "");

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(nullable = false, length = 64)
    private String pteid;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String severity = "low";

    @Builder.Default
    @Column(name = "risk_score", nullable = false)
    private int riskScore = 0;

    @Builder.Default
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}