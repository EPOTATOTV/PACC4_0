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
 * v4.1 多渠道客服工单（工单/邮件/QQ/Discord/管理员专属通道）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_support_ticket")
public class SupportTicket {

    @Id
    private String ticketId;

    private String pteid;

    /** email / qq / discord / ticket / admin */
    private String channel;

    private String subject;

    private String body;

    /** open / in_progress / resolved / closed */
    @Builder.Default
    private String status = "open";

    /** 分配到的客服/admin。 */
    private String assignee;

    private String resolution;

    private Instant createdAt;

    private Instant updatedAt;
}
