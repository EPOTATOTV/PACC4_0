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
 * 远程查端会话。管理员审查被锁玩家的安全取证通道（WebRTC + mTLS）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_inspect_session")
public class InspectSession {

    @Id
    private String sessionId;

    private String pteid;

    private String alertId;

    private String operator;

    /** QUEUED / ACTIVE / DONE / TIMEOUT / CANCELLED */
    @Builder.Default
    private String state = "QUEUED";

    /** confirmed / false_positive / pending */
    private String conclusion;

    /** 全程审计留痕（简化：存一段记录文本） */
    @Builder.Default
    private String auditLog = "";

    /** 单次查端最长 30 分钟 */
    private Instant expiresAt;

    private Instant startedAt;
}