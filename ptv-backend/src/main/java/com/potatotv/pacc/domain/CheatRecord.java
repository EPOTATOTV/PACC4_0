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
 * 并机警告 / 作弊记录（永久留存，含查端结论）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_cheat_record")
public class CheatRecord {

    @Id
    private String recordId;

    private String pteid;

    /** 关联的红屏告警 ID（用于查端误报时精确撤销对应记录）。 */
    private String alertId;

    private String cheatType;

    private int level;

    private int riskScore;

    /** 证据哈希链：本条记录对应审计链节点 */
    private String prevHash;

    private String recordHash;

    private String inspectConclusion;

    @Builder.Default
    private boolean revoked = false;

    private Instant occurredAt;
}