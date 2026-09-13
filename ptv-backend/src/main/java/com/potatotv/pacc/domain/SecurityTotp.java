package com.potatotv.pacc.domain;

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
 * 玩家 TOTP 两步验证：每 PTEID 一条，secret 为 Base32 种子。
 * 密钥为敏感数据，仅存服务端，不对外泄露明文种子之外的可逆信息。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_security_totp")
public class SecurityTotp {

    @Id
    private String pteid;

    @Column(length = 64, nullable = false)
    private String secret;

    @Builder.Default
    private boolean enabled = false;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private Instant updatedAt;

    /** 一次性恢复码（SHA-256 摘要 JSON 数组），仅首次生成时明文展示一次。 */
    @Column(name = "recovery_codes", columnDefinition = "LONGTEXT")
    private String recoveryCodes;

    /** 已使用的恢复码摘要集合 JSON。 */
    @Column(name = "recovery_used", columnDefinition = "LONGTEXT")
    private String recoveryUsed;
}