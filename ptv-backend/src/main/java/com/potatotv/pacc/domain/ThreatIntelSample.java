package com.potatotv.pacc.domain;

import jakarta.persistence.Entity;
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
 * v4.6 威胁情报样本：可疑外挂样本的静态指纹与自动生成规则。
 * 由哈希聚类归族，规则经运营审核（主动学习回流）后可提升为正式特征库。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_threat_intel_sample", indexes = {
        @Index(name = "idx_ti_created", columnList = "created_at"),
        @Index(name = "idx_ti_family", columnList = "family")
})
public class ThreatIntelSample {

    public enum Status { NEW, REVIEWED }

    @Id
    private String id;

    private String pteid;

    private String edition;

    private String md5;

    private String sha1;

    /** 聚类族名（由静态指纹哈希派生）。 */
    private String family;

    /** AI 语义家族标签（K-Means 指纹聚类后赋名，如 CLUSTER_0 …，或匹配命中的家族）。 */
    private String familyLabel;

    /** 静态维度摘要（结构字符串/指标），参与规则生成。 */
    @Lob
    private String staticDims;

    /** 自动生成的检测规则（特征表达式 JSON）。 */
    @Lob
    private String generatedRule;

    /** 自动样本分析报告（严重度/指标/建议，JSON 文本）。 */
    @Lob
    private String autoAnalysis;

    @Builder.Default
    private Status status = Status.NEW;

    /** 人工复核结论（确认真样本）。 */
    private Boolean confirmed;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant reviewedAt;

    private String reviewer;
}