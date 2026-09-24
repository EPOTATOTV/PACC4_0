-- =====================================================================
-- V19: 地图 BP（Ban/Pick）功能
-- 说明：管理端维护地图池/地图条目；对局前由蓝红双方在裁判监视下
--      Ban/Pick 地图。建 4 张新表并扩展 t_match_session 记录 BP 结果。
--      表与实体 @Entity/@Table 严格对应，生产 ddl-auto=validate 校验一致。
-- =====================================================================

-- ---------------------------------------------------------------
-- 1. 地图池
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_map_pool (
    pool_id        VARCHAR(255) NOT NULL COMMENT '地图池主键(UUID)',
    tournament_id  VARCHAR(255) COMMENT '关联赛事(可空=通用池)',
    name           VARCHAR(255) NOT NULL COMMENT '地图池名称',
    game_mode      VARCHAR(255) COMMENT '游戏模式',
    edition        VARCHAR(64)  COMMENT '版本/游戏版本',
    description    VARCHAR(4000) COMMENT '描述',
    active         BIT          NOT NULL DEFAULT TRUE COMMENT '是否启用',
    map_count      INT          NOT NULL DEFAULT 0 COMMENT '地图数量(冗余统计)',
    created_by     VARCHAR(255),
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6),
    PRIMARY KEY (pool_id),
    KEY idx_map_pool_tourney (tournament_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 2. 地图条目
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_map_entry (
    map_id          VARCHAR(255) NOT NULL COMMENT '地图主键(UUID)',
    pool_id         VARCHAR(255) NOT NULL COMMENT '所属池(FK)',
    name            VARCHAR(255) NOT NULL COMMENT '地图名',
    name_en         VARCHAR(255) COMMENT '英文名',
    map_type        VARCHAR(64)  COMMENT '地图类型',
    author          VARCHAR(255) COMMENT '作者',
    version         VARCHAR(64)  COMMENT '版本',
    difficulty      VARCHAR(64)  COMMENT '难度',
    thumbnail_url   VARCHAR(512) COMMENT '缩略图',
    preview_images  TEXT COMMENT '预览图(JSON数组)',
    description     VARCHAR(4000) COMMENT '描述',
    download_url    VARCHAR(512) COMMENT '下载地址',
    ban_count       INT          NOT NULL DEFAULT 0 COMMENT '被Ban次数',
    pick_count      INT          NOT NULL DEFAULT 0 COMMENT '被Pick次数',
    win_rate_blue   DOUBLE       NOT NULL DEFAULT 0 COMMENT '蓝方胜率(0-1)',
    win_rate_red    DOUBLE       NOT NULL DEFAULT 0 COMMENT '红方胜率(0-1)',
    active          BIT          NOT NULL DEFAULT TRUE COMMENT '是否启用',
    order_no        INT          NOT NULL DEFAULT 0 COMMENT '排序',
    created_by      VARCHAR(255),
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6),
    PRIMARY KEY (map_id),
    KEY idx_map_entry_pool (pool_id, order_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 3. BP 会话
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_map_bp_session (
    bp_session_id          VARCHAR(255) NOT NULL COMMENT 'BP会话主键(UUID)',
    tournament_id          VARCHAR(255),
    match_id               VARCHAR(255),
    stage_id               VARCHAR(255),
    pool_id                VARCHAR(255) NOT NULL COMMENT '地图池(FK)',
    format                 ENUM('BO1','BO3','BO5') NOT NULL COMMENT '赛制',
    status                 ENUM('PENDING','ACTIVE','PAUSED','COMPLETED','CANCELLED') NOT NULL DEFAULT 'PENDING' COMMENT '会话状态',
    blue_enrollment_id     VARCHAR(255) COMMENT '蓝方报名ID',
    red_enrollment_id      VARCHAR(255) COMMENT '红方报名ID',
    blue_team_name         VARCHAR(255) COMMENT '蓝方队名',
    red_team_name          VARCHAR(255) COMMENT '红方队名',
    turn_index             INT          NOT NULL DEFAULT 0 COMMENT '当前回合步长(权威状态)',
    current_turn           VARCHAR(32)  COMMENT '当前回合(如 BLUE_BAN)',
    current_round          INT          NOT NULL DEFAULT 1 COMMENT '当前轮(一次Pick为一轮)',
    total_rounds           INT          NOT NULL COMMENT '总轮数(=赛制地图数)',
    turn_timeout_seconds   INT          NOT NULL DEFAULT 60 COMMENT '每回合超时秒数',
    start_time             DATETIME(6),
    end_time               DATETIME(6),
    current_turn_deadline  DATETIME(6)  COMMENT '当前回合截止时间',
    selected_maps          TEXT COMMENT '最终选图(JSON数组)',
    banned_maps            TEXT COMMENT '全部被Ban图(JSON数组)',
    referee                VARCHAR(255) COMMENT '裁判',
    created_by             VARCHAR(255),
    cancel_reason          VARCHAR(255),
    created_at             DATETIME(6)  NOT NULL,
    updated_at             DATETIME(6),
    PRIMARY KEY (bp_session_id),
    KEY idx_bp_session_status (status),
    KEY idx_bp_session_tourney (tournament_id),
    KEY idx_bp_session_match (match_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 4. BP 操作记录（审计）
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS t_map_bp_action (
    action_id            VARCHAR(255) NOT NULL COMMENT '操作主键(UUID)',
    bp_session_id        VARCHAR(255) NOT NULL COMMENT '所属会话(FK)',
    round_no             INT          NOT NULL COMMENT '轮次',
    team                 ENUM('BLUE','RED') NOT NULL COMMENT '所属方',
    action_type          ENUM('BAN','PICK') NOT NULL COMMENT '操作类型',
    map_id               VARCHAR(255) COMMENT '涉及地图',
    map_name             VARCHAR(255) COMMENT '地图名',
    operator_pteid       VARCHAR(255) COMMENT '操作者PTEID(系统=SYSTEM)',
    operator_device_fp   VARCHAR(1024) COMMENT '操作者设备指纹',
    client_ip            VARCHAR(64)  COMMENT '操作者IP',
    response_time_ms     BIGINT       COMMENT '响应耗时(ms)',
    timeout              BIT          NOT NULL DEFAULT FALSE COMMENT '是否超时自动操作',
    created_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (action_id),
    KEY idx_bp_action_session (bp_session_id, round_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ---------------------------------------------------------------
-- 5. 扩展对局会话：记录 BP 选图结果
-- ---------------------------------------------------------------
ALTER TABLE t_match_session
    ADD COLUMN selected_maps   TEXT COMMENT 'BP最终选图(JSON数组)' NULL,
    ADD COLUMN bp_session_id   VARCHAR(255) COMMENT '关联BP会话' NULL;