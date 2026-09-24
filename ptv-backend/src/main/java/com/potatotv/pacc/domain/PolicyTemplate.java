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
 * v5.0 生态：场景策略模板（PVP/PVE/创造/小游戏/直播）。
 * <p>actionsJson 为处置项 JSON 数组 [{scopeType,scopeValue,action,severity,note}]，
 * 供"一键应用"时安全解析为 DeterPolicy 记录。该字段经沙箱校验器校验后再落库/应用。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_policy_template")
public class PolicyTemplate {

    @Id
    private String templateId;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(nullable = false, length = 32)
    private String scene;

    @Column(length = 512)
    private String description;

    /** 处置项 JSON 数组。显式 4000：默认 255 装不下 JSON。 */
    @Column(nullable = false, length = 4000)
    private String actionsJson;

    private String createdBy;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}