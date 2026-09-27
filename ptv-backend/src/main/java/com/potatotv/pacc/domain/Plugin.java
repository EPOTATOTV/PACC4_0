package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v5.0 插件市场：插件。
 * <p>开发者提交插件 → 平台审核 → 发布/评分/下载。签名用于校验包未被篡改；
 * 插件类型对齐路线图（检测规则/策略模板/服务器集成/通知/数据分析）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_plugin")
public class Plugin {

    public enum Type {
        DETECTION_RULE, POLICY_TEMPLATE, SERVER_INTEGRATION, NOTIFICATION, DATA_ANALYTIC
    }

    /** DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / REMOVED */
    public static final String ST_DRAFT = "DRAFT";
    public static final String ST_PENDING = "PENDING_REVIEW";
    public static final String ST_PUBLISHED = "PUBLISHED";
    public static final String ST_REJECTED = "REJECTED";
    public static final String ST_REMOVED = "REMOVED";

    @Id
    private String pluginId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Type type;

    @Column(nullable = false, length = 64)
    private String author;

    @Column(nullable = false, length = 32)
    private String pluginVersion;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = ST_DRAFT;

    @Column(length = 64)
    private String signatureSha256;

    @Column(length = 255)
    private String packageUrl;

    @Builder.Default
    @Column(nullable = false)
    private long downloads = 0;

    @Builder.Default
    @Column(nullable = false)
    private int ratingCount = 0;

    @Builder.Default
    @Column(nullable = false)
    private int ratingSum = 0;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    private Instant publishedAt;

    /** 平均评分，保留 1 位小数。 */
    public double avgRating() {
        return ratingCount == 0 ? 0 : Math.round(ratingSum * 10.0 / ratingCount) / 10.0;
    }
}