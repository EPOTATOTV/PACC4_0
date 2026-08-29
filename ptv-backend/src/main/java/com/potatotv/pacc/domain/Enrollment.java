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
 * 参赛门禁：赛事主办方将一个 PTEID 与"许可设备指纹"绑定并审批。
 * 比赛期间玩家必须使用被许可的设备登录，否则判为违规。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_enrollment", indexes = {
        @Index(name = "idx_enroll_pteid", columnList = "pteid"),
        @Index(name = "idx_enroll_tourney", columnList = "tournamentId")
})
public class Enrollment {

    public enum Status { PENDING, APPROVED, REJECTED }

    @Id
    @Builder.Default
    private String enrollmentId = java.util.UUID.randomUUID().toString();

    /** 赛事标识（可空：通用联赛）。 */
    private String tournamentId;

    private String pteid;

    private String displayName;

    /** 许可的设备指纹（脱敏标识）。 */
    @Column(length = 1024)
    private String permittedDeviceFingerprint;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Builder.Default
    private Instant createdAt = Instant.now();

    private Instant approvedAt;

    private String operator;

    private String note;

    /** 主办方分配的队伍名（Discord 式标签卡片用）。 */
    private String teamName;

    /** 队伍徽章颜色（十六进制，如 #58a6ff）。 */
    private String teamColor;
}