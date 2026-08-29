package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
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
 * 比赛对局会话：一场比赛发给选手的入场令牌（随机强随机生成），
 * 与选手 PTEID、报名时许可的设备指纹强绑定。入场时校验令牌存在、
 * 会话有效、且当前活动设备与许可设备一致，实现"对局级设备隔离"。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_match_session", indexes = {
        @Index(name = "idx_match_pteid", columnList = "pteid"),
        @Index(name = "idx_match_status", columnList = "status")
})
public class MatchSession {

    public enum Status { ACTIVE, ENDED }

    /** 对局 token（随机强随机生成）。 */
    @Id
    private String matchId;

    private String tournamentId;

    private String pteid;

    /** 该场次允许使用的设备指纹（= 报名时许可设备，会话开始快照）。 */
    @Column(length = 1024)
    private String deviceFingerprint;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Builder.Default
    private Instant startedAt = Instant.now();

    private Instant expiresAt;

    private Instant endedAt;

    private String operator;

    private Instant lastSeenAt;
}