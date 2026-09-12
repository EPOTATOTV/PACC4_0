package com.potatotv.pacc.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 地图 BP 操作记录：每回合的 Ban/Pick/Skip/Fallback 审计明细。
 * 记录操作者 PTEID、设备指纹、IP、响应耗时与是否超时自动操作，用于裁判复核与追责。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_map_bp_action", indexes = {
        @Index(name = "idx_bp_action_session", columnList = "bpSessionId, roundNo")
})
public class MapBanPickAction {

    public enum ActionType { BAN, PICK }

    @Id
    @Builder.Default
    private String actionId = java.util.UUID.randomUUID().toString();

    private String bpSessionId;

    @Builder.Default
    private int roundNo = 1;

    @Enumerated(EnumType.STRING)
    private MapBanPickSession.Side team;

    @Enumerated(EnumType.STRING)
    private ActionType actionType;

    private String mapId;

    private String mapName;

    private String operatorPteid;

    @jakarta.persistence.Column(length = 1024)
    private String operatorDeviceFp;

    @jakarta.persistence.Column(length = 64)
    private String clientIp;

    private Long responseTimeMs;

    @Builder.Default
    private boolean timeout = false;

    @Builder.Default
    private Instant createdAt = Instant.now();
}