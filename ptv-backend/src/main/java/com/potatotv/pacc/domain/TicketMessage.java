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
 * 玩家工单会话消息：客服 / 玩家之间的对话记录，按 ticketId 顺序回溯。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_ticket_message")
public class TicketMessage {

    @Id
    private String id;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    /** 发言人标识：玩家固定为 "player"，客服为对应操作者身份。 */
    @Column(nullable = false)
    private String responder;

    @Column(length = 4000)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}