-- v5.4 APM 应用性能监控：原始采样 / 小时聚合 / 阈值告警三张表
-- 设计文档（§2 APM）中的表名为 t_apm_metrics / t_apm_metrics_hourly，本库一律单数命名，
-- 故落为 t_apm_metric / t_apm_metric_hourly，字段与语义与文档一致。
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动建表，因此这里不用 MySQL JSON 类型、
-- 不做分区，tags 用 LONGTEXT 承载（实体侧为 @Lob String），保证 H2 也能干净建表。

CREATE TABLE t_apm_metric (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    pteid        VARCHAR(64)  NOT NULL,
    platform     VARCHAR(16)  NOT NULL,
    client_ver   VARCHAR(32)  NOT NULL,
    metric_time  DATETIME(6)  NOT NULL,
    metric_name  VARCHAR(64)  NOT NULL,
    metric_value DOUBLE       NOT NULL,
    metric_type  VARCHAR(16)  NOT NULL DEFAULT 'gauge',
    tags         LONGTEXT     NULL,
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_apm_pteid_time (pteid, metric_time),
    KEY idx_apm_name_time (metric_name, metric_time),
    KEY idx_apm_platform_time (platform, metric_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_apm_metric_hourly (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    platform     VARCHAR(16) NOT NULL,
    client_ver   VARCHAR(32) NOT NULL,
    metric_hour  DATETIME(6) NOT NULL,
    metric_name  VARCHAR(64) NOT NULL,
    avg_value    DOUBLE      NOT NULL,
    p50_value    DOUBLE      NOT NULL,
    p95_value    DOUBLE      NOT NULL,
    p99_value    DOUBLE      NOT NULL,
    max_value    DOUBLE      NOT NULL,
    sample_count BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_apm_hourly (platform, client_ver, metric_hour, metric_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_apm_alert (
    id          VARCHAR(64)  NOT NULL,
    alert_key   VARCHAR(128) NOT NULL DEFAULT '',
    alert_name  VARCHAR(64)  NOT NULL,
    metric_name VARCHAR(64)  NOT NULL,
    platform    VARCHAR(16)  NOT NULL DEFAULT '',
    client_ver  VARCHAR(32)  NOT NULL DEFAULT '',
    level       VARCHAR(4)   NOT NULL,
    threshold   DOUBLE       NOT NULL,
    observed    DOUBLE       NOT NULL,
    message     VARCHAR(512) NOT NULL DEFAULT '',
    status      VARCHAR(16)  NOT NULL DEFAULT 'OPEN',
    occurred_at DATETIME(6)  NOT NULL,
    acked_by    VARCHAR(64)  NULL,
    acked_at    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_apm_alert_status (status, occurred_at),
    KEY idx_apm_alert_key (alert_key, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;