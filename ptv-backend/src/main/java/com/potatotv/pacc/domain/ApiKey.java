package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 开放 API 密钥（v4.8）：第三方/租户出示 {@code keyId + secret} 派生 HMAC 签名调用
 * {@code /api/v1/**}。密钥不落明文，仅存由平台主密钥 AES-GCM 加密后的密文。
 * <ul>
 *   <li>scopes：READ / READ,WRITE（写操作如远程解锁需 WRITE）；</li>
 *   <li>categories：可访问 API 类别（逗号分隔），空表示全部；</li>
 *   <li>ipWhitelist：绑定调用来源 IP，空表示不限制；</li>
 *   <li>plan：决定默认限流（免费 100 / 专业 1000 / 企业 10000 每小时）。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_api_key")
public class ApiKey {

    @Id
    @Column(name = "key_id")
    private String keyId;

    @Column(nullable = false, length = 128)
    private String name;

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String tenantId = "platform";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String plan = "PRO";

    /** 密钥密文（平台主密钥加密），禁止明文落库/返回。 */
    @Lob
    @Column(nullable = false)
    private String secretEnc;

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String scopes = "READ";

    @Column(length = 255)
    private String categories;

    @Column(length = 255)
    private String ipWhitelist;

    @Builder.Default
    @Column(nullable = false)
    private int rateLimitPerHour = 1000;

    @Column(length = 512)
    private String webhookUrl;

    @Column(length = 255)
    private String webhookSecret;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private Instant lastUsedAt;

    private String createdBy;

    public boolean hasWriteScope() {
        return scopes != null && scopes.toUpperCase().contains("WRITE");
    }
}