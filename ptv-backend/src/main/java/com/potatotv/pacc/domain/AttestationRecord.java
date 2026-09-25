package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * v5.4 §3.6 远程证明挑战应答结果。
 *
 * <p>服务端发随机 nonce，客户端须在极短时间内回一段「代码段摘要 + 配置摘要 + 用 WSS 同一共享密钥签的名」。
 * 服务端独立核验 nonce 新鲜度、响应时延、HMAC 签名与已知good摘要注册表，从而不依赖客户端自述的安全状态。</p>
 *
 * <p>无论通过还是失败都落一条记录：失败原因（reason）是排查端侧被篡改/时钟异常的关键证据，
 * 通过率则由 {@code status} 聚合得出。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_attestation_record", indexes = {
        @Index(name = "idx_attest_pteid", columnList = "pteid, created_at"),
        @Index(name = "idx_attest_status", columnList = "status, created_at")
})
public class AttestationRecord {

    public enum Status { PASS, FAIL }

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Builder.Default
    @Column(name = "challenge_id", nullable = false, length = 64)
    private String challengeId = "";

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String pteid = "";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String platform = "";

    @Builder.Default
    @Column(name = "client_ver", nullable = false, length = 32)
    private String clientVer = "";

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String nonce = "";

    /** 挑战时登记的代码段摘要。 */
    @Builder.Default
    @Column(name = "code_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String codeHash = "";

    /** 挑战时登记的配置摘要。 */
    @Builder.Default
    @Column(name = "config_hash", nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String configHash = "";

    /** 客户端自述的运行时状态快照（可空），仅留痕。 */
    @Lob
    @Column(name = "runtime_state", length = Integer.MAX_VALUE)
    private String runtimeState;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Status status;

    @Builder.Default
    @Column(nullable = false, length = 64)
    private String reason = "";

    /** 客户端上报的应答耗时；超阈即判失败，是「快速应答」约束的量化依据。 */
    @Builder.Default
    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs = 0L;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}