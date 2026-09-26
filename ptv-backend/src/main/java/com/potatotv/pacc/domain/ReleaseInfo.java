package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * 客户端版本发布：按平台×渠道记录版本、灰度放量、强制更新与崩溃率元数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_release")
public class ReleaseInfo {

    @Id
    private String id;

    /** windows / android / ios / harmony / linux / macos。 */
    @Column(nullable = false, length = 16)
    private String platform;

    /** stable / beta / alpha / dev / canary。 */
    @Column(nullable = false, length = 24)
    private String channel;

    @Column(nullable = false, length = 48)
    private String version;

    @Column(name = "build_no", nullable = false)
    private int buildNo;

    @Column(length = 4000)
    private String notes;

    @Column(name = "download_url", length = 500)
    private String downloadUrl;

    @Column(length = 128)
    private String sha256;

    @Column(name = "min_app_version", length = 48)
    private String minAppVersion;

    /** 全量包字节数。 */
    @Builder.Default
    @Column(name = "file_size", nullable = false)
    private long fileSize = 0;

    /** 全量包 RSA-2048 签名（Base64），未签名为空。 */
    @Column(length = 1024)
    private String signature;

    /** 差分包基线版本：仅当与端侧 current_version 精确相等时才下发 delta。 */
    @Column(name = "delta_from_version", length = 48)
    private String deltaFromVersion;

    @Column(name = "delta_url", length = 500)
    private String deltaUrl;

    /** 差分包 SHA-256（十六进制，不带前缀）。 */
    @Column(name = "delta_sha256", length = 128)
    private String deltaSha256;

    /** 差分包字节数。 */
    @Builder.Default
    @Column(name = "delta_size", nullable = false)
    private long deltaSize = 0;

    /** 白名单放量开关。 */
    @Builder.Default
    @Column(name = "manual_enabled", nullable = false)
    private boolean manualEnabled = false;

    /** 强制更新开关。 */
    @Builder.Default
    @Column(name = "forced_enabled", nullable = false)
    private boolean forcedEnabled = false;

    /** 崩溃率（%），展示型元数据。 */
    @Builder.Default
    @Column(name = "crash_rate_pct", nullable = false)
    private BigDecimal crashRatePct = BigDecimal.ZERO;

    /** DRAFT / PUBLISHED / ARCHIVED。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "DRAFT";

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}