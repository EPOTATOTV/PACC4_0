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
 * v5.2 §7.3 查端回放录像元数据：录像密文落盘，本表只存元数据与解密所需密钥。
 *
 * <p>密钥放在库里的取舍：录像文件本身加密（AES-256-GCM），具备磁盘访问权限的人拿不到画面；
 * 能解密的是有库权限 + 管理端权限的人。密钥不由服务端固定密钥派生，是为了避免「换个部署就全解不开」，
 * 也避免一个密钥泄露导致历史录像全部失效。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_replay_recording", indexes = {
        @Index(name = "idx_replay_pteid", columnList = "pteid"),
        @Index(name = "idx_replay_alert", columnList = "alert_id"),
        @Index(name = "idx_replay_expires", columnList = "expires_at")
})
public class ReplayRecording {

    @Id
    @Column(nullable = false, length = 64)
    private String id;

    @Builder.Default
    @Column(name = "alert_id", nullable = false, length = 64)
    private String alertId = "";

    @Builder.Default
    @Column(name = "pteid", nullable = false, length = 64)
    private String pteid = "";

    /** 密文文件名（相对录像库目录）。 */
    @Builder.Default
    @Column(name = "storage_path", nullable = false, length = 255)
    private String storagePath = "";

    @Builder.Default
    @Column(name = "frames", nullable = false)
    private int frames = 0;

    @Builder.Default
    private int width = 0;

    @Builder.Default
    private int height = 0;

    @Builder.Default
    private int fps = 0;

    @Builder.Default
    @Column(name = "duration_ms", nullable = false)
    private long durationMillis = 0L;

    /** 密文字节数。 */
    @Builder.Default
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes = 0L;

    /** 明文（AVI）字节数。 */
    @Builder.Default
    @Column(name = "plain_size", nullable = false)
    private long plainSize = 0L;

    /** 明文 SHA-256（完整性留痕，下载时可复核）。 */
    @Builder.Default
    @Column(nullable = false, length = 64, columnDefinition = "CHAR(64)")
    private String sha256 = "";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String format = "MJPEG-AVI";

    /** 解密密钥（Base64，AES-256）。 */
    @Builder.Default
    @Column(name = "enc_key", nullable = false, length = 128)
    private String encKey = "";

    /** GCM IV（Base64，12 字节）。 */
    @Builder.Default
    @Column(name = "enc_iv", nullable = false, length = 64)
    private String encIv = "";

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    /** 保留到期时间（默认上传后 30 天，到期自动删除）。 */
    @Builder.Default
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt = Instant.now();
}