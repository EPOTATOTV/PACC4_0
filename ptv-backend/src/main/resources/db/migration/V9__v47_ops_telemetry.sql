-- v4.7 自动化运维：客户端崩溃/性能上报表 + 远程配置表
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动建表。
-- @Lob 列对应实体上的 @Lob String，MySQL 下按 LONGTEXT 落库（H2 的 local profile 走 ddl-auto，不读本文件）。

CREATE TABLE t_client_crash_report (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    pteid VARCHAR(32) NULL,
    client_version VARCHAR(32) NOT NULL,
    os VARCHAR(16) NOT NULL,
    arch VARCHAR(16) NOT NULL,
    platform ENUM('WINDOWS','ANDROID','IOS','HARMONY') NOT NULL DEFAULT 'WINDOWS',
    stack_trace LONGTEXT NULL,
    context_json LONGTEXT NULL,
    controller VARCHAR(128) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_crash_created ON t_client_crash_report (created_at DESC);

CREATE TABLE t_client_telemetry (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    pteid VARCHAR(32) NULL,
    client_version VARCHAR(32) NOT NULL,
    os VARCHAR(16) NOT NULL,
    cpu_percent DOUBLE NOT NULL DEFAULT 0,
    mem_mb BIGINT NOT NULL DEFAULT 0,
    fps_impact_percent DOUBLE NULL,
    detection_latency_ms BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_telemetry_created ON t_client_telemetry (created_at DESC);

CREATE TABLE t_remote_config (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    category ENUM('DETECTION','SCAN','REDSCREEN','THROTTLE') NOT NULL DEFAULT 'DETECTION',
    int_value INT NULL,
    double_value DOUBLE NULL,
    bool_value TINYINT(1) NULL,
    updated_by VARCHAR(64) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);