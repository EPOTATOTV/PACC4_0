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
 * PTEID 反作弊账号。
 * <p>账号体系完全独立，与游戏账号无任何绑定关系。密码使用 Argon2id 加盐哈希存储。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_account")
public class Account {

    @Id
    private String pteid;

    @Column(nullable = false, unique = true)
    private String email;

    private String phone;

    /** Minecraft 游戏 ID（工单中心登录凭证之一）。 */
    @Column(unique = true)
    private String mcid;

    /** EaseCation 平台 ID（工单中心登录凭证之一）。 */
    private String ecid;

    /** QQ 号（工单中心登录凭证之一）。 */
    @Column(unique = true)
    private String qq;

    /** 网易 UUID（可选，仅作账号关联；提供格式说明不做强校验）。 */
    private String neteaseUuid;

    /** Argon2id 加盐哈希，禁止存明文。 */
    @Column(nullable = false)
    private String passwordHash;

    /** 密码重置令牌的 SHA-256（仅存哈希，禁用明文），用于邮箱找回。 */
    private String resetTokenHash;

    /** 密码重置令牌过期时间。 */
    private Instant resetExpiresAt;

    /** 信誉评分 0-100。 */
    @Builder.Default
    @Column(nullable = false)
    private int reputation = 100;

    /** normal / suspicious / high_risk / locked_inspect。 */
    @Builder.Default
    @Column(nullable = false)
    private String status = "normal";

    @Builder.Default
    @Column(nullable = false)
    private int totalRedscreen = 0;

    @Builder.Default
    @Column(nullable = false)
    private int failedLogins = 0;

    /** 设备指纹绑定（CPU/主板/硬盘/MAC 组合）。 */
    @Column(length = 1024)
    private String deviceFingerprint;

    @Builder.Default
    @Column(nullable = false)
    private Instant registeredAt = Instant.now();

    private Instant lastRedScreenTime;

    private Instant lockedUntil;
}