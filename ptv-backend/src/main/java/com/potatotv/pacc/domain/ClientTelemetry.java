package com.potatotv.pacc.domain;

import jakarta.persistence.Entity;
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
 * v4.7 客户端性能上报：客户端周期性的运行时开销采样（CPU / 内存 / 帧率影响 / 检测延迟），
 * 用于评估反作弊组件对玩家机器的资源占用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_client_telemetry", indexes = {
        @Index(name = "idx_telemetry_created", columnList = "created_at")
})
public class ClientTelemetry {

    @Id
    @Builder.Default
    private String id = UUID.randomUUID().toString().replace("-", "");

    private String pteid;

    @Builder.Default
    private String clientVersion = "";

    @Builder.Default
    private String os = "";

    @Builder.Default
    private double cpuPercent = 0;

    @Builder.Default
    private long memMb = 0;

    private Double fpsImpactPercent;

    private Long detectionLatencyMs;

    @Builder.Default
    private Instant createdAt = Instant.now();
}