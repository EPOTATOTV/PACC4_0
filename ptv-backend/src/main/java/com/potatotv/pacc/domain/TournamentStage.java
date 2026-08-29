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
 * 赛事进程阶段（轮次）：一场赛事由若干可按序、可自由编辑的阶段组成。
 * 因每届赛制不同（资格赛/小组赛/淘汰赛/决赛/自定义），管理员可对阶段
 * 进行增删、排序、改标题/时间、改状态、填结果，从而适配任意赛制。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_tournament_stage", indexes = {
        @Index(name = "idx_stage_tourney", columnList = "tournamentId")
})
public class TournamentStage {

    public enum Kind { QUALIFIER, GROUP, KNOCKOUT, FINAL, CUSTOM }

    public enum Status { PENDING, ACTIVE, DONE }

    @Id
    @Builder.Default
    private String stageId = java.util.UUID.randomUUID().toString();

    private String tournamentId;

    /** 展示顺序（小号在前）。 */
    private int orderNo;

    private String title;

    @Enumerated(EnumType.STRING)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    private Status status;

    private Instant startTime;

    private Instant endTime;

    /** 结果备注（如获胜 PTEID / 比分 / 说明）。 */
    private String resultNote;

    private String note;

    @Builder.Default
    private Instant createdAt = Instant.now();
}