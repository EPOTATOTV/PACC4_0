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
 * 检测器配置：以键值对承载各检测器的启停开关与阈值/参数（JSON），
 * 供运营灰度开关与调参，消费方按 detectorKey 读取。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_detector_config")
public class DetectorConfig {

    @Id
    private String id;

    @Column(name = "detector_key", nullable = false, unique = true, length = 128)
    private String detectorKey;

    @Column(nullable = false, length = 128)
    private String name;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** 阈值 / 参数 JSON（不进敏感字段）。 */
    @Column(name = "meta_json", length = 4000)
    private String metaJson;

    @Column(name = "updated_by", length = 128)
    private String updatedBy;

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}