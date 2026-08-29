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
 * 红屏警告事件。系统唯一的作弊响应手段（不封禁）。
 * level=2 二级(确认作弊) / level=3 三级(严重作弊)。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_redscreen_alert")
public class RedscreenAlert {

    @Id
    private String alertId;

    private int level;

    /** cheat_type 作弊类型 */
    private String cheatType;

    /** 作弊者脱敏账号（仅后台可查看全量） */
    @Column(name = "pteid_masked")
    private String pteidMasked;

    private String pteid;

    private int riskScore;

    private String edition;

    /** PENDING_INSPECT / CONFIRMED / FALSE_POSITIVE */
    @Builder.Default
    private String state = "PENDING_INSPECT";

    private String inspectConclusion;

    private long broadcastOnline;

    private long broadcastAck;

    private Instant occurredAt;

    private Instant resolvedAt;
}