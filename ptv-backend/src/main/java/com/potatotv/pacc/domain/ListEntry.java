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
 * 黑白名单条目：按名单方向（黑/白）× 对象类型（玩家/设备/IP/进程/特征）管理。
 * 用于检测侧对照（命中黑名单加重、白名单豁免）与运维干预。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_list_entry")
public class ListEntry {

    @Id
    private String id;

    /** 名单方向 BLACK / WHITE。 */
    @Column(name = "list_type", nullable = false, length = 16)
    private String listType;

    /** 对象类型 PLAYER / DEVICE / IP / PROCESS / FEATURE。 */
    @Column(name = "entry_type", nullable = false, length = 24)
    private String entryType;

    @Column(nullable = false, length = 255)
    private String value;

    @Column(length = 500)
    private String reason;

    /** ACTIVE / INACTIVE。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;
}