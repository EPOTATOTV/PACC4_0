package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 地图条目：地图池内的一张地图。维护展示素材（缩略图/预览图/描述/下载地址）
 * 与历史统计（被 Ban / 被 Pick 次数、蓝红双方胜率），供 BP 决策与选手浏览。
 * previewImages 以 JSON 字符串（数组）存取。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_map_entry", indexes = {
        @Index(name = "idx_map_entry_pool", columnList = "poolId, orderNo")
})
public class MapEntry {

    @Id
    @Builder.Default
    private String mapId = java.util.UUID.randomUUID().toString();

    private String poolId;

    @Builder.Default
    private String name = "";

    private String nameEn;

    private String mapType;

    private String author;

    private String version;

    private String difficulty;

    @Column(length = 512)
    private String thumbnailUrl;

    /** 预览图以 JSON 数组字符串存储，如 ["url1","url2"]。 */
    @Column(columnDefinition = "TEXT")
    private String previewImages;

    @Column(length = 4000)
    private String description;

    @Column(length = 512)
    private String downloadUrl;

    @Builder.Default
    private int banCount = 0;

    @Builder.Default
    private int pickCount = 0;

    @Builder.Default
    private double winRateBlue = 0;

    @Builder.Default
    private double winRateRed = 0;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    private int orderNo = 0;

    private String createdBy;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}