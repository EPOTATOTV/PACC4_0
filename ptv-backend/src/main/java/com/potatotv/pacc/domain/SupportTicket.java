package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v4.7 客服工单：分类（申诉/技术/账号/功能/商务/举报）、优先级（P0~P3）、SLA 首响跟踪、状态流转。
 * <p>状态：OPEN -> RESPONDED -> RESOLVED -> CLOSED；优先级：P0(最高)~P3(普通)。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_support_ticket")
public class SupportTicket {

    @Id
    private String id;

    /** 关联玩家 PTEID，可空（匿名/渠道工单）。 */
    private String pteid;

    /** APPEAL | TECHNICAL | ACCOUNT | FEATURE | BUSINESS | REPORT */
    private String category;

    private String title;

    @Lob
    private String description;

    /** OPEN | RESPONDED | RESOLVED | CLOSED */
    @Builder.Default
    private String status = "OPEN";

    /** P0 | P1 | P2 | P3 */
    @Builder.Default
    private String priority = "P3";

    /** 分配给的处理人。 */
    private String assignee;

    private Instant firstReplyAt;

    private Instant resolvedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private Instant updatedAt;
}