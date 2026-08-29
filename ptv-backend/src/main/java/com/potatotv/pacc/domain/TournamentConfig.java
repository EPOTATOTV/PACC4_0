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
 * 赛事报名配置（按届一份）：选手经腾讯文档收集表填写报名资料后，
 * 在该平台完成设备绑定与提交参赛申请。此处存放本场赛事的收集表链接、
 * 截止时间与名称，供玩家报名页和管理端配置。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_tournament_config")
public class TournamentConfig {

    /** 以 tournamentId 作为主键，一场赛事一份配置。 */
    @Id
    private String tournamentId;

    private String title;

    /** 腾讯文档收集表链接。 */
    @Column(length = 2000)
    private String tencentDocUrl;

    /** 报名截止时间。 */
    private Instant applyDeadline;

    /** 是否开放报名。 */
    @Builder.Default
    private boolean allowRegister = true;

    private String note;

    @Builder.Default
    private Instant updatedAt = Instant.now();
}