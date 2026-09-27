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
 * v4.1 玩家在线申诉（红屏/误报申诉）。玩家通过自助门户提交，客服/admin 处理。
 * <p>v4.4 增强：自动初筛证据快照 + 多级审核流（auto → level1 → level2 → final）+ 误报恢复。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_appeal")
public class Appeal {

    @Id
    private String appealId;

    private String pteid;

    /** 关联的红屏告警 ID（可选）。 */
    private String alertId;

    private String reason;

    private String description;

    /** pending / in_review / approved / rejected */
    @Builder.Default
    private String status = "pending";

    /** 审核流阶段：auto（自动初筛）/ level1（客服）/ level2（分析师）/ final（终审）。 */
    @Builder.Default
    private String reviewStage = "auto";

    /** 当前处理角色：sys / support / analyst / techlead。 */
    @Builder.Default
    private String reviewRole = "sys";

    /** 自动初筛分 0-100。 */
    @Builder.Default
    private int prescreenScore = 0;

    /**
     * v5.2 §7.4 AI 自动复核结论：
     * {@code PENDING}（待复核）/ {@code MISREPORT}（确认误报，已自动撤销）/
     * {@code CONFIRMED}（确认违规，转人工）/ {@code INCONCLUSIVE}（证据不足，转人工）。
     */
    @Builder.Default
    @Column(length = 16)
    private String autoReview = "PENDING";

    /** 证据快照（作弊记录哈希、告警 ID、检测证据摘要、设备信息等 JSON）。 */
    @Column(length = 4000)
    private String evidenceJson;

    private String reviewer;

    private String reviewComment;

    private Instant createdAt;

    private Instant reviewedAt;
}
