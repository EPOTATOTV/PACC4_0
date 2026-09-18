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
 * 下载站发布物：每个平台/产物一份当前公映版本，供 /api/dl/latest 下发。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_dl_release")
public class DlRelease {

    @Id
    private String id;

    /** WIN / LNX / APK / IOS / HMY / JVM。 */
    @Column(nullable = false, length = 16)
    private String platform;

    /** client / probe。 */
    @Column(nullable = false, length = 48)
    private String artifact;

    @Column(nullable = false, length = 32)
    private String version;

    @Column(nullable = false, length = 512)
    private String fileUrl;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Builder.Default
    @Column(nullable = false)
    private long sizeBytes = 0;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Builder.Default
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}