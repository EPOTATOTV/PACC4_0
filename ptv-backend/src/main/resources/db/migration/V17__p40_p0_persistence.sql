-- =====================================================================
-- V17: v5.0 P0 补充功能的持久化承载
-- 说明：把此前进程内承载的过渡状态落库，正式表与实体 @Entity/@Table 严格对应，
--      生产 ddl-auto=validate 校验一致。默认数据（告警规则 / 内置角色）由
--      控制器 @PostConstruct 在空表时播种，SQL 只负责建表。
-- =====================================================================

-- 1. 玩家 TOTP 两步验证（每 PTEID 一条，secret 为 Base32 密钥）
CREATE TABLE IF NOT EXISTS t_security_totp (
    pteid      VARCHAR(255) NOT NULL,
    secret     VARCHAR(64)  NOT NULL,
    enabled    BIT          NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL,
    updated_at DATETIME(6),
    PRIMARY KEY (pteid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 2. 玩家工单会话消息（客服 / 玩家对话记录）
CREATE TABLE IF NOT EXISTS t_ticket_message (
    id          VARCHAR(255) NOT NULL,
    ticket_id   VARCHAR(255) NOT NULL,
    responder   VARCHAR(255) NOT NULL,
    content     VARCHAR(4000),
    created_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_tmessage_ticket (ticket_id),
    KEY idx_tmessage_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3. 管理端告警规则（阈值 / 冷却 / 送达通道，channels 逗号分隔）
CREATE TABLE IF NOT EXISTS t_alert_rule (
    id            VARCHAR(255) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    scope         VARCHAR(255),
    condition     VARCHAR(255),
    threshold     INT,
    cooldown_min  INT,
    enabled       BIT NOT NULL DEFAULT FALSE,
    channels      VARCHAR(255),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 4. 管理端角色与权限（permissions 为模块×操作矩阵的 JSON）
CREATE TABLE IF NOT EXISTS t_admin_role (
    id            VARCHAR(255) NOT NULL,
    name          VARCHAR(255) NOT NULL,
    role_key      VARCHAR(255) NOT NULL,
    description   VARCHAR(4000),
    builtin       BIT NOT NULL DEFAULT FALSE,
    member_count  INT NOT NULL DEFAULT 0,
    permissions   LONGTEXT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_key (role_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 5. 玩家通知已读标记（避免无界增长，按 pteid+notif_id 去重）
CREATE TABLE IF NOT EXISTS t_player_notif_read (
    id        VARCHAR(255) NOT NULL,
    pteid     VARCHAR(255) NOT NULL,
    notif_id  VARCHAR(255) NOT NULL,
    read_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_notif_read_pteid (pteid),
    UNIQUE KEY uk_notif_read (pteid, notif_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;