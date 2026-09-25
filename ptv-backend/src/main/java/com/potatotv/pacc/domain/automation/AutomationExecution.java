package com.potatotv.pacc.domain.automation;

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
 * §4.3.3 自动化执行审计日志：每次自动动作都留痕（成功/失败/跳过），支持回滚标记。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_automation_execution")
public class AutomationExecution {

    public static final String ST_SUCCESS = "SUCCESS";
    public static final String ST_FAILED = "FAILED";
    public static final String ST_SKIPPED = "SKIPPED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rule_code", nullable = false, length = 64)
    private String ruleCode;

    @Column(name = "action_code", nullable = false, length = 64)
    private String actionCode;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = ST_SUCCESS;

    @Column(length = 2000)
    private String detail;

    @Builder.Default
    @Column(nullable = false)
    private boolean reversible = true;

    @Builder.Default
    @Column(nullable = false)
    private boolean reverted = false;

    @Column(name = "revert_detail", length = 2000)
    private String revertDetail;

    @Builder.Default
    @Column(name = "executed_at", nullable = false)
    private Instant executedAt = Instant.now();

    @Column(name = "executed_by", length = 128)
    private String executedBy;
}