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
 * 信誉分变更日志：记录每次加减分的 delta、变更后分值、原因与来源。
 * 配合 {@link Account#getReputation()} 现数字段提供审计明细与趋势。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_reputation_log")
public class ReputationLog {

    @Id
    private String id;

    @Column(nullable = false, length = 255)
    private String pteid;

    /** 本次变更量（可为负）。 */
    @Column(nullable = false)
    private int delta;

    /** 变更后信誉分。 */
    @Column(name = "score_after", nullable = false)
    private int scoreAfter;

    @Column(nullable = false, length = 128)
    private String reason;

    @Column(length = 64)
    private String source;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}