package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 开放 API 调用审计（v4.8）：记录每次 /api/v1 调用的调用方/接口/IP/返回码/耗时。
 * <p>仅记录元信息（method/path/ip/status），不记录 query/body，符合访问日志防敏感泄露约束。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_api_usage_log",
        indexes = {
                @Index(name = "idx_api_usage_key_time", columnList = "apiKeyId, createdAt"),
                @Index(name = "idx_api_usage_tenant", columnList = "tenantId, createdAt")
        })
public class ApiUsageLog {

    @Id
    private String id;

    @Column(nullable = false)
    private String apiKeyId;

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String tenantId = "platform";

    @Column(nullable = false, length = 8)
    private String method;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(length = 64)
    private String ip;

    @Column(nullable = false)
    private int statusCode;

    private Long latencyMs;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}