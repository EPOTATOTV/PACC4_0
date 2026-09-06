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
 * v4.7 主动威慑策略：对已确认恶意样本/家族/特征设置分级处置（MONITOR / BLOCK / ISOLATE / IGNORE），
 * 供事件风控在命中时按策略联动，形成从"威胁情报确认"到"运营处置"的闭环。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_deter_policy")
public class DeterPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 策略作用域类型：FAMILY / SAMPLE / SIGNATURE。 */
    @Column(nullable = false, length = 16)
    private String scopeType;

    /** 作用域标识：familyLabel / sampleId / 特征码名。 */
    @Column(nullable = false, length = 128)
    private String scopeValue;

    /** 处置动作：MONITOR / BLOCK / ISOLATE / IGNORE。 */
    @Column(nullable = false, length = 16)
    private String action;

    @Builder.Default
    @Column(nullable = false)
    private int severity = 3;

    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(length = 512)
    private String note;

    private String createdBy;

    @Builder.Default
    @Column(nullable = false)
    private Instant createdAt = Instant.now();
}