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
 * 全局动效配置：档位 + 各类动效开关 + 红屏模板。单一默认行。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_effect_config")
public class EffectConfig {

    @Id
    private String id;

    /** off / gentle / standard / strong。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String motionLevel = "standard";

    /** 各动效开关 JSON。 */
    @Builder.Default
    @Column(nullable = false, length = 4000)
    private String effectsJson = "{}";

    /** 红屏动效模板 id。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String redscreenTemplate = "standard";

    /** 红屏模板参数 JSON。 */
    @Builder.Default
    @Column(nullable = false, length = 4000)
    private String redscreenJson = "{}";

    private String updatedBy;

    @Builder.Default
    @Column(nullable = false)
    private Instant updatedAt = Instant.now();
}