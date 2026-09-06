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
 * v4.8 多租户：租户分级（免费/专业/企业）与功能矩阵。
 * <p>平台租户层，为开放 API 与新增能力提供租户隔离与分级控制；
 * 不强制存量业务表物理拆分（数据隔离由 Open API 密钥的 tenant 维度承载）。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_tenant")
public class Tenant {

    @Id
    private String tenantId;

    @Column(nullable = false, length = 128)
    private String name;

    /** FREE / PRO / ENTERPRISE。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String plan = "FREE";

    /** ACTIVE / SUSPENDED。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(nullable = false)
    private int maxAdmins = 1;

    @Builder.Default
    @Column(nullable = false)
    private int dataRetentionDays = 30;

    /** 是否开放标准 API 访问。 */
    @Builder.Default
    @Column(nullable = false)
    private boolean apiAccess = false;

    /** 是否开放 Webhook 推送。 */
    @Builder.Default
    @Column(nullable = false)
    private boolean webhookAccess = false;

    /** 是否支持自定义策略。 */
    @Builder.Default
    @Column(nullable = false)
    private boolean customPolicy = false;

    @Column(length = 255)
    private String slaDescription;

    @Column(length = 512)
    private String note;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    private String createdBy;

    public boolean hasApi() {
        return apiAccess;
    }
}