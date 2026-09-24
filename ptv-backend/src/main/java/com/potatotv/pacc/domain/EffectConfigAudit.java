package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 动效配置变更审计：每次保存动效配置追加一行，保留变更后的完整快照与差异摘要。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_effect_config_audit")
public class EffectConfigAudit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 操作人标识。 */
    @Builder.Default
    @Column(nullable = false, length = 64)
    private String changedBy = "unknown";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String motionLevel = "standard";

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String redscreenTemplate = "standard";

    /** 差异摘要（改了哪些档位/开关），供管理端直接展示，不必前端再 diff。 */
    @Builder.Default
    @Column(nullable = false, length = 512)
    private String summary = "";

    @Builder.Default
    @Column(nullable = false, length = 4000)
    private String effectsJson = "{}";

    @Builder.Default
    @Column(nullable = false, length = 4000)
    private String redscreenJson = "{}";

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}