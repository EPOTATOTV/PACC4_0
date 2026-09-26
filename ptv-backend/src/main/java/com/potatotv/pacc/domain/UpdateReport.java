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
 * 端侧更新结果上报（设计文档 §4.11 POST /v1/update/report）。
 * 服务端据此统计更新成功率 / 失败率与回滚，不承载任何封禁语义。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_update_report")
public class UpdateReport {

    @Id
    @Column(length = 36)
    private String id;

    /** 玩家/设备标识，仅用于统计，可空。 */
    @Column(length = 64)
    private String pteid;

    /** windows / android / ios / harmony / linux / macos。 */
    @Column(length = 16)
    private String platform;

    @Column(name = "from_version", length = 48)
    private String fromVersion;

    @Column(name = "to_version", length = 48)
    private String toVersion;

    /** success / failed / rolled_back / skipped。 */
    @Builder.Default
    @Column(nullable = false, length = 24)
    private String status = "failed";

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Builder.Default
    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();
}