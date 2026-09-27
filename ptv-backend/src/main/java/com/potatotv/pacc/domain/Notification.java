package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 统一通知主表：管理端公告（pteid 为空 = 广播）与定向通知（pteid 非空）。
 * 玩家端通知中心与派生态通知（红屏/申诉/工单）并存展示。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_notification")
public class Notification {

    @Id
    private String id;

    /** 目标玩家；为空表示全局广播公告。 */
    @Column
    private String pteid;

    /** 归属域：SYSTEM / COMPETITION / SECURITY ... */
    @Builder.Default
    @Column(nullable = false, length = 32)
    private String scope = "SYSTEM";

    /** 类型：SYSTEM_ANNOUNCEMENT 等。 */
    @Builder.Default
    @Column(nullable = false, length = 32)
    private String type = "SYSTEM_ANNOUNCEMENT";

    @Column(nullable = false, length = 255)
    private String title;

    /** length 取 int 上限：Hibernate 据此推导为 longtext，与迁移脚本一致。 */
    @Lob
    @Column(length = Integer.MAX_VALUE)
    private String content;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String priority = "NORMAL";

    /** ACTIVE / RETRACTED。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column
    private Instant expiresAt;
}