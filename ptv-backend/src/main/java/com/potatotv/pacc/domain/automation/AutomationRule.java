package com.potatotv.pacc.domain.automation;

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
 * §4.3.3 自动化响应规则：trigger（触发条件）→ action（动作）映射表。
 *
 * <p>内置五条规则（{@link #code} 对应内置触发器）；管理端可启用/停用，
 * 停用后评估器不再执行（no-op），且执行历史仍可追溯。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_automation_rule")
public class AutomationRule {

    @Id
    @Column(length = 64)
    private String id;

    /** 内置触发器编码，如 {@code FAMILY_DETECTION_BURST}。 */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    /** 触发条件的人类可读表达式（展示用）。 */
    @Column(name = "trigger_expr", nullable = false, length = 255)
    private String triggerExpr;

    /** 动作编码，对应 {@code AutomationActionHandler#code()}。 */
    @Column(name = "action_code", nullable = false, length = 64)
    private String actionCode;

    /** 触发阈值（百分比 / 次数 / 条数，语义随 code 而定）。 */
    @Builder.Default
    @Column(nullable = false)
    private double threshold = 0.0;

    /** 观测窗口（分钟）。 */
    @Builder.Default
    @Column(name = "window_min", nullable = false)
    private int windowMin = 60;

    /** 两次执行的最小间隔（分钟），防抖。 */
    @Builder.Default
    @Column(name = "cooldown_min", nullable = false)
    private int cooldownMin = 30;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    /** 是否内置规则（内置不可删除）。 */
    @Builder.Default
    @Column(nullable = false)
    private boolean builtin = true;

    @Column(name = "last_fired_at")
    private Instant lastFiredAt;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Builder.Default
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}