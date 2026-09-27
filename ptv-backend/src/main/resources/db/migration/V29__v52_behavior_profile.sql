-- v5.2 §6.2 行为画像系统 + §7.2 信誉系统（0-1000 分制）持久化。
--
-- 三张新表：
--   t_player_behavior_profile     每个 PTEID 一行：由在线统计（Welford）折叠出的行为基线 + 0-1000 信誉分
--   t_player_daily_pattern        按「日 + 小时」聚合的在线时长与事件量，供 DailyPattern 展示与趋势
--   t_player_hardware_fingerprint 玩家硬件指纹历史，支撑「设备指纹突变 → 标记可疑」
--
-- 画像只存统计量（均值/标准差/样本数），不落原始特征样本：样本随观测增量折叠，
-- 因此 128 维特征不会在本表膨胀，玩家历史观测次数再多也只是常数级存储。
--
-- ---------------------------------------------------------------
-- 信誉分制兼容说明（0-100 → 0-1000），本迁移不做任何破坏性改写：
--   1) 既有 t_account.reputation 与 t_reputation_log 的历史行保持原有 0-100 口径，一律不动；
--   2) v5.2 的权威分值落在 t_player_behavior_profile.reputation_score（初始 600，范围 0-1000）；
--   3) v5.2 的审计仍复用 t_reputation_log 表，但 source 以 'v52:' 前缀标记来源事件，
--      读取端据此区分口径：source 非 'v52:' 前缀的历史行按 0-100 口径，×10 换算到 0-1000 后比较
--      （换算见 ReputationV2Service#legacyScale）。
-- ---------------------------------------------------------------

CREATE TABLE IF NOT EXISTS t_player_behavior_profile (
    pteid               VARCHAR(64) NOT NULL COMMENT 'PTEID（一个玩家一行）',
    mean_cps            DOUBLE      NOT NULL DEFAULT 0 COMMENT '点击速度在线均值（feature_click_cps）',
    cps_std             DOUBLE      NOT NULL DEFAULT 0 COMMENT '点击速度总体标准差',
    mean_aim_smoothness DOUBLE      NOT NULL DEFAULT 0 COMMENT '瞄准平滑度在线均值（feature_aim_smoothness）',
    aim_smoothness_std  DOUBLE      NOT NULL DEFAULT 0 COMMENT '瞄准平滑度总体标准差',
    mean_speed          DOUBLE      NOT NULL DEFAULT 0 COMMENT '移动速度比在线均值（feature_speed_ratio）',
    speed_std           DOUBLE      NOT NULL DEFAULT 0 COMMENT '移动速度比总体标准差',
    total_sessions      BIGINT      NOT NULL DEFAULT 0 COMMENT '累计会话数',
    total_detections    BIGINT      NOT NULL DEFAULT 0 COMMENT '累计检测命中次数',
    false_positives     BIGINT      NOT NULL DEFAULT 0 COMMENT '其中经复核确认为误报的次数',
    reputation_score    INT         NOT NULL DEFAULT 600 COMMENT '0-1000 信誉分（v5.2 §7.2，初始 600）',
    reputation_level    VARCHAR(16) NOT NULL DEFAULT 'OBSERVED' COMMENT 'TRUSTED / NORMAL / OBSERVED / RISK / HIGH_RISK',
    profile_version     INT         NOT NULL DEFAULT 1 COMMENT '画像结构版本（结构变更时递增）',
    sample_count        BIGINT      NOT NULL DEFAULT 0 COMMENT '已折叠的观测样本数（不存原始样本）',
    stability           DOUBLE      NOT NULL DEFAULT 0 COMMENT '画像稳定度 0-1（约 10 小时观测后趋于 1）',
    first_seen_at       DATETIME(6) NOT NULL COMMENT '首次观测时间',
    updated_at          DATETIME(6) NOT NULL COMMENT '最近一次更新（观测/重建）时间',
    PRIMARY KEY (pteid),
    KEY idx_behavior_profile_score (reputation_score),
    KEY idx_behavior_profile_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_player_daily_pattern (
    id              BIGINT      AUTO_INCREMENT NOT NULL,
    pteid           VARCHAR(64) NOT NULL COMMENT 'PTEID',
    pattern_date    DATE        NOT NULL COMMENT '统计日',
    hour_of_day     INT         NOT NULL COMMENT '小时 0-23（实时时段分布）',
    session_seconds BIGINT      NOT NULL DEFAULT 0 COMMENT '该时段累计在线秒数',
    event_count     BIGINT      NOT NULL DEFAULT 0 COMMENT '该时段累计观测/检测事件数',
    PRIMARY KEY (id),
    UNIQUE KEY uk_daily_pattern (pteid, pattern_date, hour_of_day),
    KEY idx_daily_pattern_date (pattern_date)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_player_hardware_fingerprint (
    id               BIGINT       AUTO_INCREMENT NOT NULL,
    pteid            VARCHAR(64)  NOT NULL COMMENT 'PTEID',
    fingerprint_hash VARCHAR(128) NOT NULL COMMENT '硬件指纹摘要（不存明文指纹）',
    first_seen_at    DATETIME(6)  NOT NULL COMMENT '该指纹首次出现时间',
    last_seen_at     DATETIME(6)  NOT NULL COMMENT '该指纹最近出现时间',
    seen_count       BIGINT       NOT NULL DEFAULT 1 COMMENT '累计出现次数',
    PRIMARY KEY (id),
    UNIQUE KEY uk_player_fingerprint (pteid, fingerprint_hash),
    KEY idx_player_fingerprint_pteid (pteid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
