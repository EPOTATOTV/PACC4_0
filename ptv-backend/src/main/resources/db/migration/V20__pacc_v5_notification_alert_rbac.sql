-- =====================================================================
-- V20: PACC v5.0 统一通知 / 告警事件 / 2FA 恢复码
-- 说明：新增通知主表、通知设置、告警事件表；并为 t_security_totp 追加恢复码列。
--      默认数据（内置角色/告警规则）仍由控制器空表播种，SQL 只负责建表。
-- =====================================================================

-- 1. 统一通知主表（pteid 为空表示广播公告，非空表示定向通知）
CREATE TABLE IF NOT EXISTS t_notification (
    id          VARCHAR(255) NOT NULL,
    pteid       VARCHAR(255),
    scope       VARCHAR(32)  NOT NULL DEFAULT 'SYSTEM',
    type        VARCHAR(32)  NOT NULL DEFAULT 'SYSTEM_ANNOUNCEMENT',
    title       VARCHAR(255) NOT NULL,
    content     LONGTEXT,
    priority    VARCHAR(16)  NOT NULL DEFAULT 'NORMAL',
    status      VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    created_at  DATETIME(6)  NOT NULL,
    expires_at  DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_notification_pteid (pteid),
    KEY idx_notification_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 2. 玩家通知偏好设置（settings 为 JSON 文本，如推送达通道开关）
CREATE TABLE IF NOT EXISTS t_notification_setting (
    pteid     VARCHAR(255) NOT NULL,
    settings  LONGTEXT,
    PRIMARY KEY (pteid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3. 告警事件（规则引擎触发后落库，支持生命周期 FIRING / ACKNOWLEDGED / RESOLVED）
CREATE TABLE IF NOT EXISTS t_alert_event (
    id                VARCHAR(255) NOT NULL,
    rule_id           VARCHAR(255),
    rule_name         VARCHAR(255) NOT NULL,
    severity          INT          NOT NULL DEFAULT 1,
    metric            VARCHAR(64),
    condition_value   VARCHAR(64),
    threshold         INT,
    actual_value      FLOAT,
    status            VARCHAR(16)  NOT NULL DEFAULT 'FIRING',
    fired_at          DATETIME(6)  NOT NULL,
    acknowledged_at   DATETIME(6),
    acknowledged_by   VARCHAR(255),
    resolved_at       DATETIME(6),
    resolution_note   VARCHAR(4000),
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_alert_event_status (status),
    KEY idx_alert_event_fired (fired_at),
    KEY idx_alert_event_rule (rule_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 4. 为 t_security_totp 追加 2FA 恢复码（SHA-256 摘要 JSON 与已用集合 JSON）
ALTER TABLE t_security_totp ADD COLUMN recovery_codes LONGTEXT;
ALTER TABLE t_security_totp ADD COLUMN recovery_used LONGTEXT;