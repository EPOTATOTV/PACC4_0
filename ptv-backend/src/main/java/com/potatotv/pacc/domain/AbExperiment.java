package com.potatotv.pacc.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A/B 测试实验：在检测算法不同维度上做对照实验，采集暴露/命中/误报指标，
 * 用 z 检验判定实验组（variantB）相对对照组（variantA）是否显著更优后采纳发布。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_ab_experiment")
public class AbExperiment {

    @Id
    private String id;

    private String name;

    /** 实验描述，可空 */
    private String description;

    /**
     * 实验维度：THRESHOLD（阈值）/ ALGORITHM（算法）/ SCAN（扫描）/ SIGNATURE（特征）/
     * REDSCREEN（红屏）/ PERFORMANCE（性能）
     */
    private String dimension;

    /** 对照组变体 */
    private String variantA;

    /** 实验组变体 */
    private String variantB;

    /** 实验组占比 0-100 */
    private int targetPercent;

    /** DRAFT / RUNNING / FINISHED / ARCHIVED */
    @Builder.Default
    private String status = "DRAFT";

    private Instant startedAt;

    private Instant endedAt;

    /** 已采集：实验曝光次数 */
    @Builder.Default
    private long metricsCtExposure = 0L;

    /** 已采集：实验命中次数 */
    @Builder.Default
    private long metricsCtDetect = 0L;

    /** 已采集：实验误报次数 */
    @Builder.Default
    private long metricsCtFalsePositive = 0L;

    /** 发布后采纳的变体（winner） */
    private String winner;

    private Instant createdAt;
}