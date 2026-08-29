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
 * v4.1 玩家在线申诉（红屏/误报申诉）。玩家通过自助门户提交，客服/admin 处理。
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

    /** pending / approved / rejected */
    @Builder.Default
    private String status = "pending";

    private String reviewer;

    private String reviewComment;

    private Instant createdAt;

    private Instant reviewedAt;
}
