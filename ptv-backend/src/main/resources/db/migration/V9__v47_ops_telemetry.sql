-- v4.7 自动化运维：客户端崩溃/性能上报表 + 远程配置表
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动建表。
-- @Lob 列在 MySQL/H2 按 Hibernate 物理类型映射（H2=CLOB），此处沿用既有 v4.6 迁移写法。

CREATE TABLE t_client_crash_report (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    pteid VARCHAR(32) NULL,
    client_version VARCHAR(32) NOT NULL,
    os VARCHAR(16) NOT NULL,
    arch VARCHAR(16) NOT NULL,
    platform VARCHAR(16) NOT NULL DEFAULT 'WINDOWS',
    stack_trace CLOB NULL,
    context_json CLOB NULL,
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
    category VARCHAR(16) NOT NULL DEFAULT 'DETECTION',
    int_value INT NULL,
    double_value DOUBLE NULL,
    bool_value TINYINT(1) NULL,
    updated_by VARCHAR(64) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);