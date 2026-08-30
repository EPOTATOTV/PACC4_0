-- =====================================================================
-- PACC v4.0 初始化表结构（Flyway V1）
-- 引擎：MySQL 8.0（utf8mb4）；H2 local 模式同样兼容（MODE=MySQL）
-- 说明：18 张业务表，均由生产 ddl-auto=validate 校验与实体一致。
-- =====================================================================

-- ---------------------------------------------------------------
-- 1. 反作弊账号（PTEID 账号体系）
-- ---------------------------------------------------------------
CREATE TABLE t_account (
    pteid                VARCHAR(255) NOT NULL,
    email                VARCHAR(255) NOT NULL,
    phone                VARCHAR(255),
    mcid                 VARCHAR(255),
    ecid                 VARCHAR(255),
    qq                   VARCHAR(255),
    netease_uuid         VARCHAR(255),
    password_hash        VARCHAR(255) NOT NULL,
    reset_token_hash     VARCHAR(255),
    reset_expires_at     DATETIME(6),
    reputation           INT    NOT NULL DEFAULT 100,
    status               VARCHAR(255) NOT NULL DEFAULT 'normal',
    total_redscreen      INT    NOT NULL DEFAULT 0,
    failed_logins        INT    NOT NULL DEFAULT 0,
    device_fingerprint   VARCHAR(1024),
    registered_at        DATETIME(6) NOT NULL,
    last_red_screen_time DATETIME(6),
    locked_until         DATETIME(6),
    PRIMARY KEY (pteid),
    CONSTRAINT uk_account_email UNIQUE (email),
    CONSTRAINT uk_account_mcid UNIQUE (mcid),
    CONSTRAINT uk_account_qq UNIQUE (qq)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 2. 作弊记录（并机警告 / 查端结论，永久留存）
-- ---------------------------------------------------------------
CREATE TABLE t_cheat_record (
    record_id         VARCHAR(255) NOT NULL,
    pteid             VARCHAR(255),
    alert_id          VARCHAR(255),
    cheat_type        VARCHAR(255),
    level             INT,
    risk_score        INT,
    prev_hash         VARCHAR(255),
    record_hash       VARCHAR(255),
    inspect_conclusion VARCHAR(255),
    revoked           BIT NOT NULL DEFAULT FALSE,
    occurred_at       DATETIME(6),
    PRIMARY KEY (record_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 3. 红屏警告事件（系统唯一作弊响应，触发查端）
-- ---------------------------------------------------------------
CREATE TABLE t_redscreen_alert (
    alert_id          VARCHAR(255) NOT NULL,
    level             INT,
    cheat_type        VARCHAR(255),
    pteid_masked      VARCHAR(255),
    pteid             VARCHAR(255),
    risk_score        INT,
    edition           VARCHAR(255),
    state             VARCHAR(255) NOT NULL DEFAULT 'PENDING_INSPECT',
    inspect_conclusion VARCHAR(255),
    broadcast_online  BIGINT NOT NULL DEFAULT 0,
    broadcast_ack     BIGINT NOT NULL DEFAULT 0,
    occurred_at       DATETIME(6),
    resolved_at       DATETIME(6),
    PRIMARY KEY (alert_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 4. 登录设备记录（玩家门户展示历史设备，标 active）
-- ---------------------------------------------------------------
CREATE TABLE t_device (
    device_id          VARCHAR(255) NOT NULL,
    pteid              VARCHAR(255),
    device_fingerprint VARCHAR(1024),
    platform           VARCHAR(255),
    device_name        VARCHAR(255),
    ip                 VARCHAR(255),
    first_login_at     DATETIME(6),
    last_login_at      DATETIME(6),
    active             BIT NOT NULL DEFAULT FALSE,
    PRIMARY KEY (device_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 5. 玩家端检测事件上报（含嵌入式证据）
-- ---------------------------------------------------------------
CREATE TABLE t_detection_event (
    id                 VARCHAR(255) NOT NULL,
    pteid              VARCHAR(255),
    event_type         VARCHAR(255),
    severity           VARCHAR(255),
    edition            SMALLINT,
    client_risk_score  INT,
    evidence_process_name  VARCHAR(255),
    evidence_memory_region VARCHAR(255),
    evidence_signature_hit VARCHAR(255),
    evidence_detail_json   VARCHAR(255),
    client_version     VARCHAR(255),
    os_info            VARCHAR(255),
    occurred_at        DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 6. 特征库特征码（基岩/JAVA 分版管理，灰度发布）
-- ---------------------------------------------------------------
CREATE TABLE t_signature (
    id               VARCHAR(255) NOT NULL,
    name             VARCHAR(255),
    pattern          VARCHAR(255),
    risk_level       INT,
    edition          SMALLINT,
    library_version  VARCHAR(255),
    state            VARCHAR(255) NOT NULL DEFAULT 'DRAFT',
    gray_percent     INT NOT NULL DEFAULT 0,
    created_by       VARCHAR(255),
    created_at       DATETIME(6),
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 7. 玩家在线申诉（红屏/误报）
-- ---------------------------------------------------------------
CREATE TABLE t_appeal (
    appeal_id      VARCHAR(255) NOT NULL,
    pteid          VARCHAR(255),
    alert_id       VARCHAR(255),
    reason         VARCHAR(255),
    description    VARCHAR(255),
    status         VARCHAR(255) NOT NULL DEFAULT 'pending',
    reviewer       VARCHAR(255),
    review_comment VARCHAR(255),
    created_at     DATETIME(6),
    reviewed_at    DATETIME(6),
    PRIMARY KEY (appeal_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 8. 赛事风控嫌疑（证据哈希链）
-- ---------------------------------------------------------------
CREATE TABLE t_suspicion_flag (
    flag_id          VARCHAR(255) NOT NULL,
    pteid            VARCHAR(255),
    kind             VARCHAR(255),
    detail           VARCHAR(255),
    weight           INT,
    evidence_summary VARCHAR(2048),
    prev_chain_hash  VARCHAR(255),
    chain_hash       VARCHAR(255),
    status           VARCHAR(255),
    created_at       DATETIME(6) NOT NULL,
    reviewed_at      DATETIME(6),
    reviewer         VARCHAR(255),
    review_comment   VARCHAR(255),
    during_match     BIT NOT NULL DEFAULT FALSE,
    PRIMARY KEY (flag_id),
    KEY idx_flag_pteid (pteid),
    KEY idx_flag_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 9. 远程查端会话（WebRTC + mTLS 取证通道）
-- ---------------------------------------------------------------
CREATE TABLE t_inspect_session (
    session_id  VARCHAR(255) NOT NULL,
    pteid       VARCHAR(255),
    alert_id    VARCHAR(255),
    operator    VARCHAR(255),
    state       VARCHAR(255) NOT NULL DEFAULT 'QUEUED',
    conclusion  VARCHAR(255),
    audit_log   VARCHAR(255) NOT NULL DEFAULT '',
    expires_at  DATETIME(6),
    started_at  DATETIME(6),
    PRIMARY KEY (session_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 10. 单次玩家登录事件（宏观风控聚合底座）
-- ---------------------------------------------------------------
CREATE TABLE t_login_event (
    id                 BIGINT NOT NULL AUTO_INCREMENT,
    pteid              VARCHAR(255),
    device_fingerprint VARCHAR(1024),
    ip                 VARCHAR(64),
    created_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_login_pteid (pteid),
    KEY idx_login_ip (ip)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 11. 绑定设备使用过的外设
-- ---------------------------------------------------------------
CREATE TABLE t_peripheral (
    peripheral_id      VARCHAR(255) NOT NULL,
    pteid              VARCHAR(255),
    device_fingerprint VARCHAR(1024),
    kind               VARCHAR(255),
    vendor             VARCHAR(255),
    model              VARCHAR(255),
    connected          BIT NOT NULL DEFAULT FALSE,
    first_seen_at      DATETIME(6) NOT NULL,
    last_seen_at       DATETIME(6),
    PRIMARY KEY (peripheral_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 12. 比赛对局会话（对局级设备隔离）
-- ---------------------------------------------------------------
CREATE TABLE t_match_session (
    match_id           VARCHAR(255) NOT NULL,
    tournament_id      VARCHAR(255),
    pteid              VARCHAR(255),
    device_fingerprint VARCHAR(1024),
    status             VARCHAR(255),
    started_at         DATETIME(6) NOT NULL,
    expires_at         DATETIME(6),
    ended_at           DATETIME(6),
    operator           VARCHAR(255),
    last_seen_at       DATETIME(6),
    PRIMARY KEY (match_id),
    KEY idx_match_pteid (pteid),
    KEY idx_match_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 13. 参赛门禁（PTEID 与许可设备绑定）
-- ---------------------------------------------------------------
CREATE TABLE t_enrollment (
    enrollment_id                 VARCHAR(255) NOT NULL,
    tournament_id                 VARCHAR(255),
    pteid                         VARCHAR(255),
    display_name                  VARCHAR(255),
    permitted_device_fingerprint  VARCHAR(1024),
    status                        VARCHAR(255),
    created_at                    DATETIME(6) NOT NULL,
    approved_at                   DATETIME(6),
    operator                      VARCHAR(255),
    note                          VARCHAR(255),
    team_name                     VARCHAR(255),
    team_color                    VARCHAR(255),
    PRIMARY KEY (enrollment_id),
    KEY idx_enroll_pteid (pteid),
    KEY idx_enroll_tourney (tournament_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 14. 赛事报名配置
-- ---------------------------------------------------------------
CREATE TABLE t_tournament_config (
    tournament_id    VARCHAR(255) NOT NULL,
    title            VARCHAR(255),
    tencent_doc_url  VARCHAR(2000),
    apply_deadline   DATETIME(6),
    allow_register   BIT NOT NULL DEFAULT TRUE,
    note             VARCHAR(255),
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (tournament_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 15. 赛事公告
-- ---------------------------------------------------------------
CREATE TABLE t_tournament_notice (
    notice_id      VARCHAR(255) NOT NULL,
    tournament_id  VARCHAR(255),
    title          VARCHAR(255),
    content        VARCHAR(4000),
    pinned         BIT NOT NULL DEFAULT FALSE,
    operator       VARCHAR(255),
    created_at     DATETIME(6) NOT NULL,
    updated_at     DATETIME(6),
    PRIMARY KEY (notice_id),
    KEY idx_notice_tourney (tournament_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 16. 赛事进程阶段（可编辑轮次）
-- ---------------------------------------------------------------
CREATE TABLE t_tournament_stage (
    stage_id       VARCHAR(255) NOT NULL,
    tournament_id  VARCHAR(255),
    order_no       INT,
    title          VARCHAR(255),
    kind           VARCHAR(255),
    status         VARCHAR(255),
    start_time     DATETIME(6),
    end_time       DATETIME(6),
    result_note    VARCHAR(255),
    note           VARCHAR(255),
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (stage_id),
    KEY idx_stage_tourney (tournament_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 17. 多渠道客服工单
-- ---------------------------------------------------------------
CREATE TABLE t_support_ticket (
    ticket_id  VARCHAR(255) NOT NULL,
    pteid      VARCHAR(255),
    channel    VARCHAR(255),
    subject    VARCHAR(255),
    body       VARCHAR(255),
    status     VARCHAR(255) NOT NULL DEFAULT 'open',
    assignee   VARCHAR(255),
    resolution VARCHAR(255),
    created_at DATETIME(6),
    updated_at DATETIME(6),
    PRIMARY KEY (ticket_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 18. 管理员登录日志（审计，不落敏感明文）
-- ---------------------------------------------------------------
CREATE TABLE t_admin_login_log (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    identity   VARCHAR(128),
    method     VARCHAR(16) NOT NULL,
    role       VARCHAR(32),
    result     VARCHAR(16) NOT NULL,
    ip         VARCHAR(64),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;