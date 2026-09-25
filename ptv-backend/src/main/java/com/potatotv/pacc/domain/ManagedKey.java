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
import java.util.Locale;

/**
 * v5.4 §5 托管密钥（根密钥派生子密钥的生命周期载体）。
 *
 * <p>与 {@link ApiKey}（租户开放 API 密钥，存加密密文）不同：本表的密钥是<b>从根密钥派生</b>得来的
 * 子密钥，<b>永不落明文，也不落派生结果本身</b>——只保存「派生盐 salt + 指纹 fingerprint」。
 * 因此即便数据库被拖库，攻击者拿到的也只是一串盐和哈希，无法还原出可用密钥（根密钥仍在服务器内存/环境中）。
 * 派生算法为 HKDF-SHA256（见 {@code Hkdf}），用途经 {@code info} 参与派生，不同用途密钥互不通用。</p>
 *
 * <p>生命周期：{@code ACTIVE → ROTATED}（旧版本仅为历史密文仍可解而保留）{@code → EXPIRED}，
 * 或在泄露/异常时 {@code → REVOKED}。任何迁移都会配套写一条 {@link KeyAuditLog}。</p>
 *
 * <p>{@code purpose}/{@code state} 以字符串存库（枚举名），读取端用 {@link #parsePurpose}/{@link #parseState}
 * 容错解析：用途允许安全兜底，状态因只由服务端写入，未知值必须显式报错，绝不静默降级。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_managed_key")
public class ManagedKey {

    /** 密钥用途：决定 HKDF 的 info 域，隔离不同子密钥体系。 */
    public enum Purpose {
        CONFIG_ENCRYPT, // 配置项加密
        REPORT_ENCRYPT, // 上报数据加密
        MODEL_SIGN,     // 模型/规则包签名
        ATTESTATION,    // 端侧证明
        DB_ENCRYPT,     // 敏感字段落库加密
        API_SIGN,       // 开放 API 请求签名
        WEBHOOK_SIGN    // 回调 Webhook 签名
    }

    /** 密钥生命周期状态。 */
    public enum State {
        ACTIVE,   // 当前生效
        ROTATED,  // 已轮换（仅保留用于解密历史密文）
        EXPIRED,  // 到期失效
        REVOKED   // 因泄露/异常被吊销（不可再用）
    }

    @Id
    @Column(length = 64)
    private String id;

    /** 人类可读稳定标识（如 {@code config_encrypt-v3}），对外引用一律用它。 */
    @Column(name = "key_id", nullable = false, length = 80, unique = true)
    private String keyId;

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String purpose = Purpose.CONFIG_ENCRYPT.name();

    @Builder.Default
    @Column(nullable = false, length = 32)
    private String algorithm = "HKDF-SHA256";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String state = State.ACTIVE.name();

    /** 同用途内的轮换版本号（从 1 递增）。 */
    @Builder.Default
    @Column(nullable = false)
    private int version = 1;

    /** 派生来源，当前恒为根密钥 {@code ROOT}。 */
    @Builder.Default
    @Column(name = "derived_from", nullable = false, length = 64)
    private String derivedFrom = "ROOT";

    /** 派生盐（32 字节随机数的十六进制，非密），用于相同根密钥下隔离各子密钥。 */
    @Builder.Default
    @Column(nullable = false, length = 64)
    private String salt = "";

    /** 派生密钥指纹 {@code sha256(hex(key))}，仅用于核对/展示，不泄露密钥。 */
    @Builder.Default
    @Column(nullable = false, length = 64)
    private String fingerprint = "";

    @Builder.Default
    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy = "";

    @Builder.Default
    @Column(nullable = false, length = 255)
    private String note = "";

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    /** 轮换到期时间（创建时按 {@code pacc.security.key-rotation-days} 计算）。 */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoke_reason", length = 255)
    private String revokeReason;

    /**
     * 用途容错解析：未知/空值安全兜底为 {@link Purpose#CONFIG_ENCRYPT}。
     * <p>仅用于读取历史行，不作为写入口的校验；写入口（{@code create}）对未知用途必须显式拒绝。</p>
     */
    public static Purpose parsePurpose(String raw) {
        if (raw == null || raw.isBlank()) return Purpose.CONFIG_ENCRYPT;
        try {
            return Purpose.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Purpose.CONFIG_ENCRYPT;
        }
    }

    /**
     * 状态严格解析：状态只由服务端写入，出现未知值说明数据异常，必须显式抛错而非静默兜底。
     *
     * @throws IllegalArgumentException 状态为空或非枚举值时抛出
     */
    public static State parseState(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("密钥状态不能为空");
        }
        return State.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}