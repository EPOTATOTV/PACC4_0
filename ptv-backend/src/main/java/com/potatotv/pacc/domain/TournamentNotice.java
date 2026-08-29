package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 赛事公告：管理员面向某届赛事发布的信息（赛制说明、对阵、提醒、成绩公示等），
 * 报名选手在门户可见，提升信息触达与体验。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_tournament_notice", indexes = {
        @Index(name = "idx_notice_tourney", columnList = "tournamentId")
})
public class TournamentNotice {

    @Id
    @Builder.Default
    private String noticeId = java.util.UUID.randomUUID().toString();

    private String tournamentId;

    private String title;

    @Column(length = 4000)
    private String content;

    private boolean pinned;

    private String operator;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant updatedAt;
}