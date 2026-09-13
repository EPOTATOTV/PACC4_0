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
 * 数据导出任务：异步生成（CSV/JSON）到本地临时目录，任务就绪后凭一次性下载凭证取文件。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_export_task")
public class ExportTask {

    @Id
    private String id;

    /** accounts / redscreens / cheat_records / appeals。 */
    @Column(nullable = false, length = 64)
    private String subject;

    @Column(length = 1000)
    private String filters;

    @Builder.Default
    @Column(nullable = false, length = 16)
    private String format = "csv";

    /** QUEUED / RUNNING / READY / FAILED / EXPIRED。 */
    @Builder.Default
    @Column(nullable = false, length = 16)
    private String status = "QUEUED";

    @Column(name = "requested_by", length = 128)
    private String requestedBy;

    @Column(name = "file_path", length = 500)
    private String filePath;

    /** 一次性下载凭证：下载后即失效。 */
    @Column(name = "download_key", length = 128)
    private String downloadKey;

    @Builder.Default
    @Column(name = "row_count", nullable = false)
    private int rowCount = 0;

    @Column(name = "error_msg", length = 500)
    private String errorMsg;

    @Builder.Default
    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;
}