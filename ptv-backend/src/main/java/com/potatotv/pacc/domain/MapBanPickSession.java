package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 地图 BP（Ban/Pick）会话：一场对局的地图选择流程。
 * <ul>
 *   <li>{@code status}：PENDING（未开始）→ ACTIVE（进行中）→ COMPLETED / CANCELLED，可 PAUSED 暂停。</li>
 *   <li>{@code turnIndex} 是权威的回合步长（0 起），{@code currentTurn}/{@code currentRound} 由状态机据其派生。</li>
 *   <li>{@code selectedMaps}/{@code bannedMaps} 以 JSON 数组字符串存储。</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_map_bp_session", indexes = {
        @Index(name = "idx_bp_session_status", columnList = "status"),
        @Index(name = "idx_bp_session_tourney", columnList = "tournamentId"),
        @Index(name = "idx_bp_session_match", columnList = "matchId")
})
public class MapBanPickSession {

    public enum Status { PENDING, ACTIVE, PAUSED, COMPLETED, CANCELLED }

    public enum Format { BO1(1), BO3(3), BO5(5);

        /** 该赛制的地图数（也是总轮数）。 */
        public final int maps;

        Format(int maps) { this.maps = maps; } }

    public enum Side { BLUE, RED }

    @Id
    @Builder.Default
    private String bpSessionId = java.util.UUID.randomUUID().toString();

    private String tournamentId;

    private String matchId;

    private String stageId;

    private String poolId;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Format format = Format.BO1;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Status status = Status.PENDING;

    private String blueEnrollmentId;

    private String redEnrollmentId;

    private String blueTeamName;

    private String redTeamName;

    /** 权威回合步长（0 起），唯一推进依据；currentTurn/currentRound 由其后置派生。 */
    @Builder.Default
    private int turnIndex = 0;

    /** 当前回合标识，如 BLUE_BAN / RED_PICK；null 表示流程未开始。 */
    private String currentTurn;

    @Builder.Default
    private int currentRound = 1;

    @Builder.Default
    private int totalRounds = 1;

    @Builder.Default
    private int turnTimeoutSeconds = 60;

    private Instant startTime;

    private Instant endTime;

    private Instant currentTurnDeadline;

    /** 最终选图（JSON 数组字符串）。 */
    @Column(columnDefinition = "TEXT")
    private String selectedMaps;

    /** 全部被 Ban 图（JSON 数组字符串）。 */
    @Column(columnDefinition = "TEXT")
    private String bannedMaps;

    private String referee;

    private String createdBy;

    private String cancelReason;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }
}