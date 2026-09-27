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

/**
 * §4.2.3 多租户资源隔离：每租户的玩家数 / 检测量 / 存储配额与当前用量。
 * <p>用量随检测记录写入与存储上报累加，配额校验在写入前执行，超限即拒绝。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_tenant_quota")
public class TenantQuota {

    @Id
    @Column(name = "tenant_id", length = 64)
    private String tenantId;

    @Builder.Default
    @Column(name = "max_players", nullable = false)
    private int maxPlayers = 100;

    @Builder.Default
    @Column(name = "max_detection_volume", nullable = false)
    private long maxDetectionVolume = 100_000L;

    @Builder.Default
    @Column(name = "max_storage_mb", nullable = false)
    private long maxStorageMb = 1024L;

    @Builder.Default
    @Column(name = "used_players", nullable = false)
    private int usedPlayers = 0;

    @Builder.Default
    @Column(name = "used_detection_volume", nullable = false)
    private long usedDetectionVolume = 0L;

    @Builder.Default
    @Column(name = "used_storage_mb", nullable = false)
    private long usedStorageMb = 0L;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}