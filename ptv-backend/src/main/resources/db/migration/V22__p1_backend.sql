-- =====================================================================
-- V22: PACC v5.0 阶段2 —— P1 后端服务落表
-- 覆盖：黑白名单 / 版本发布 / 信誉分变更日志 / 数据导出任务
-- 采用 utf8mb4、InnoDB，字段与实体映射一致，Flyway validate 可校验。
-- =====================================================================

-- 1. 多类型名单（玩家/设备/IP/进程/特征；type + value 维度唯一）
CREATE TABLE IF NOT EXISTS t_list_entry (
    id          VARCHAR(255) NOT NULL,
    list_type   VARCHAR(16)  NOT NULL,        -- BLACK / WHITE
    entry_type  VARCHAR(24)  NOT NULL,        -- PLAYER / DEVICE / IP / PROCESS / FEATURE
    value       VARCHAR(255) NOT NULL,
    reason      VARCHAR(500),
    status      VARCHAR(16)  NOT NULL,        -- ACTIVE / INACTIVE
    created_by  VARCHAR(128),
    created_at  DATETIME(6)  NOT NULL,
    expires_at  DATETIME(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_list_entry (list_type, entry_type, value),
    KEY idx_list_entry_type (list_type, entry_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 2. 客户端版本发布（灰度 / 强制更新 / 崩溃率元数据）
CREATE TABLE IF NOT EXISTS t_release (
    id               VARCHAR(255) NOT NULL,
    platform         VARCHAR(16)  NOT NULL,   -- windows / android / ios / macos / linux
    channel          VARCHAR(24)  NOT NULL,   -- stable / beta / canary
    version          VARCHAR(48)  NOT NULL,
    build_no         INT          NOT NULL,
    notes            VARCHAR(4000),
    download_url     VARCHAR(500),
    sha256           VARCHAR(128),
    min_app_version  VARCHAR(48),
    manual_enabled   TINYINT(1)   NOT NULL DEFAULT 0,  -- 白名单放量
    forced_enabled   TINYINT(1)   NOT NULL DEFAULT 0,  -- 强制更新
    crash_rate_pct   DECIMAL(38,2) NOT NULL DEFAULT 0,  -- 崩溃率（%），展示型
    status           VARCHAR(16)  NOT NULL,   -- DRAFT / PUBLISHED / ARCHIVED
    published_at     DATETIME(6),
    created_by       VARCHAR(128),
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_release_platform_channel (platform, channel),
    KEY idx_release_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3. 信誉分变更日志（配合 Account.reputation 现数字段，补审计明细）
CREATE TABLE IF NOT EXISTS t_reputation_log (
    id          VARCHAR(255) NOT NULL,
    pteid       VARCHAR(255) NOT NULL,
    delta       INT          NOT NULL,
    score_after INT          NOT NULL,
    reason      VARCHAR(128) NOT NULL,
    source      VARCHAR(64),
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_reputation_log_pteid (pteid, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 4. 数据导出任务（异步：提交 -> 生成 -> 就绪 -> 下载）
CREATE TABLE IF NOT EXISTS t_export_task (
    id           VARCHAR(255) NOT NULL,
    subject      VARCHAR(64)  NOT NULL,       -- accounts / redscreens / cheat_records / appeals
    filters      VARCHAR(1000),
    format       VARCHAR(16)  NOT NULL DEFAULT 'csv',
    status       VARCHAR(16)  NOT NULL,       -- QUEUED / RUNNING / READY / FAILED / EXPIRED
    requested_by VARCHAR(128),
    file_path    VARCHAR(500),
    download_key VARCHAR(128),
    row_count    INT          NOT NULL DEFAULT 0,
    error_msg    VARCHAR(500),
    requested_at DATETIME(6)  NOT NULL,
    completed_at DATETIME(6),
    expires_at   DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_export_task_status (status),
    KEY idx_export_task_requested (requested_by, requested_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;