package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 管理端告警规则：阈值 / 冷却窗口 / 送达通道（channels 逗号分隔）。由控制器空表播种默认值。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_alert_rule")
public class AlertRule {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    /** REALTIME | QUEUE | SIGNATURE ... */
    private String scope;

    /** 触发条件（如内存写入/调试器）。列名避开 MySQL 保留字 CONDITION，否则裸写 column 名会让查询语法报错。 */
    @Column(name = "rule_condition")
    private String condition;

    private Integer threshold;

    private Integer cooldownMin;

    @Builder.Default
    private boolean enabled = false;

    /** 逗号分隔的送达通道，如 "dashboard,email"。 */
    private String channels;
}