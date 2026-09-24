package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v4.6 零日外挂检测发现：由特征分布外异常（孤立森林 + 线性自编码重构 + 行为基线偏离）产生。
 * 低置信记录进入主动学习队列，由运营人工审核回流。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_zero_day_finding", indexes = {
        @Index(name = "idx_zd_pteid", columnList = "pteid"),
        @Index(name = "idx_zd_status", columnList = "status"),
        @Index(name = "idx_zd_created", columnList = "created_at")
})
public class ZeroDayFinding {

    public enum Status { OPEN, REVIEWED }

    @Id
    private String id;

    private String pteid;

    /** BEDROCK / JAVA */
    private String edition;

    /** 孤立森林独离分数 0-1。 */
    private double isoScore;

    /** 线性自编码重构误差（原始尺度）。 */
    private double reconError;

    /** 个人行为基线偏离（归一化 z-score 范数）。 */
    private double baselineDeviation;

    /** 综合风险分 0-100 */
    private int compositeScore;

    /** LOW / MEDIUM / HIGH（与 ConfidenceTier 对齐） */
    private String confidenceTier;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private Status status = Status.OPEN;

    /** 特征摘要（参与审计与模型回流）。length 取 int 上限：Hibernate 据此推导为 longtext。 */
    @Lob
    @Column(length = Integer.MAX_VALUE)
    private String featuresJson;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant reviewedAt;

    private String reviewer;

    private String reviewComment;

    /** 人工复核结论：确认真样本 / 误报。 */
    private Boolean confirmed;
}