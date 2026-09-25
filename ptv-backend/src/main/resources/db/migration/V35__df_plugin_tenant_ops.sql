-- DF（Deep Fortress）Alpha 1.0.0 / kernel 5.4.0 §4.2.2 / §4.2.3 / §4.3.2 / §4.3.3 持久化。
--
-- 本迁移一次性建立四个章节所需的运行时表（全部 IF NOT EXISTS，可重复执行）：
--   §4.2.2 插件化检测规则：t_plugin_runtime（热加载运行时登记：状态 / CPU 用量 / 错误计数 / 沙箱 API 白名单）
--   §4.2.3 多租户 SaaS：t_tenant_quota（资源配额+用量）/ t_tenant_usage（计费计量流水）/
--                        t_tenant_detection_record（租户独立检测记录，租户隔离验收 A25 的载体）
--   §4.3.2 告警降噪：t_alert_group（聚合组）/ t_alert_suppression_rule（误报抑制）/ t_alert_notify_queue（批量通知队列）
--   §4.3.3 自动化响应：t_automation_rule（trigger→action 规则）/ t_automation_state（其他服务读取的真实开关）/
--                       t_automation_execution（执行审计与回滚留痕）
--
-- 字段口径（与实体逐列对齐，生产 ddl-auto=validate 会逐列比对 JDBC 类型码）：
--   1) 所有哈希/标识列一律 VARCHAR（不用 CHAR，避免定长右侧补位导致的比较与校验问题）；
--   2) 时间列一律 DATETIME(6)，对应 Java Instant；
--   3) 布尔列一律 TINYINT(1)，对应 Java boolean；
--   4) NOT NULL 列在实体侧均有 @Builder.Default，默认值口径与此处 DEFAULT 保持一致。

-- ============================ §4.2.2 插件运行时 ============================
CREATE TABLE IF NOT EXISTS t_plugin_runtime (
    plugin_id     VARCHAR(64)  NOT NULL COMMENT '插件市场条目 id（t_plugin.plugin_id）',
    name          VARCHAR(128) NOT NULL DEFAULT '' COMMENT '插件名（来自市集条目或插件清单）',
    version       VARCHAR(32)  NOT NULL DEFAULT '1.0.0' COMMENT '插件版本',
    class_path    VARCHAR(512) NULL COMMENT '热加载来源（jar / 目录路径）',
    state         VARCHAR(16)  NOT NULL DEFAULT 'UNLOADED' COMMENT 'LOADED / UNLOADED / ERROR',
    cpu_ms        BIGINT       NOT NULL DEFAULT 0 COMMENT '累计执行耗时（毫秒），沙箱预算判定依据',
    error_count   INT          NOT NULL DEFAULT 0 COMMENT '累计错误次数（超时 / 异常 / 越权调用）',
    declared_apis VARCHAR(512) NULL COMMENT '插件声明的沙箱 API 令牌（逗号分隔留痕）',
    loaded_at     DATETIME(6)  NULL COMMENT '最近一次加载成功时间',
    updated_at    DATETIME(6)  NOT NULL COMMENT '最近一次状态变更时间',
    PRIMARY KEY (plugin_id),
    KEY idx_plugin_runtime_state (state),
    KEY idx_plugin_runtime_updated (updated_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.2.3 多租户：配额 ============================
CREATE TABLE IF NOT EXISTS t_tenant_quota (
    tenant_id            VARCHAR(64) NOT NULL COMMENT '租户 id',
    max_players          INT         NOT NULL DEFAULT 100 COMMENT '玩家数上限',
    max_detection_volume BIGINT      NOT NULL DEFAULT 100000 COMMENT '检测量上限（条）',
    max_storage_mb       BIGINT      NOT NULL DEFAULT 1024 COMMENT '存储上限（MB）',
    used_players         INT         NOT NULL DEFAULT 0 COMMENT '已用玩家数',
    used_detection_volume BIGINT     NOT NULL DEFAULT 0 COMMENT '已用检测量',
    used_storage_mb      BIGINT      NOT NULL DEFAULT 0 COMMENT '已用存储（MB）',
    updated_at           DATETIME(6) NOT NULL COMMENT '最近一次用量更新时间',
    PRIMARY KEY (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.2.3 多租户：计费计量 ============================
CREATE TABLE IF NOT EXISTS t_tenant_usage (
    id          BIGINT       AUTO_INCREMENT NOT NULL,
    tenant_id   VARCHAR(64)  NOT NULL COMMENT '租户 id',
    metric      VARCHAR(32)  NOT NULL COMMENT 'PLAYER / DETECTION / STORAGE',
    quantity    BIGINT       NOT NULL DEFAULT 0 COMMENT '计量数量',
    unit_price  DOUBLE       NOT NULL DEFAULT 0 COMMENT '单价',
    amount      DOUBLE       NOT NULL DEFAULT 0 COMMENT '金额 = quantity × unit_price',
    occurred_at DATETIME(6)  NOT NULL COMMENT '计量发生时间',
    PRIMARY KEY (id),
    KEY idx_tenant_usage_tenant_time (tenant_id, occurred_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.2.3 多租户：数据隔离载体 ============================
CREATE TABLE IF NOT EXISTS t_tenant_detection_record (
    id          VARCHAR(64) NOT NULL COMMENT '记录主键（UUID 去连字符）',
    tenant_id   VARCHAR(64) NOT NULL COMMENT '所属租户（一切读写强制按该列过滤）',
    pteid       VARCHAR(64) NOT NULL COMMENT '玩家标识',
    event_type  VARCHAR(64) NOT NULL COMMENT '检测事件类型',
    severity    VARCHAR(16) NOT NULL DEFAULT 'low' COMMENT 'low / medium / high / critical',
    risk_score  INT         NOT NULL DEFAULT 0 COMMENT '风险分 0-100',
    occurred_at DATETIME(6) NOT NULL COMMENT '事件发生时间',
    created_at  DATETIME(6) NOT NULL COMMENT '入库时间',
    PRIMARY KEY (id),
    KEY idx_tenant_detection_tenant_time (tenant_id, occurred_at),
    KEY idx_tenant_detection_tenant_pteid (tenant_id, pteid)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.3.2 告警聚合组 ============================
CREATE TABLE IF NOT EXISTS t_alert_group (
    id            VARCHAR(64)   NOT NULL COMMENT '聚合组主键（UUID 去连字符）',
    tenant_id     VARCHAR(64)   NULL COMMENT '租户维度聚合键（可选）',
    player_id     VARCHAR(64)   NULL COMMENT '玩家维度聚合键（同一玩家多告警合并为一条）',
    family_code   VARCHAR(64)   NULL COMMENT '作弊家族维度聚合键',
    rule_id       VARCHAR(255)  NULL COMMENT '来源告警规则 id',
    severity      INT           NOT NULL DEFAULT 1 COMMENT '组内最高严重度',
    priority      VARCHAR(8)    NOT NULL DEFAULT 'P2' COMMENT 'P0 / P1 / P2 / P3',
    signal_count  INT           NOT NULL DEFAULT 1 COMMENT '被合并的原始告警数（降噪率 A23 的度量基础）',
    raw_alert_ids VARCHAR(2000) NULL COMMENT '原始告警 id 列表（逗号分隔，超长截断）',
    status        VARCHAR(16)   NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN / NOTIFIED',
    first_seen_at DATETIME(6)   NOT NULL COMMENT '组内最早告警时间',
    last_seen_at  DATETIME(6)   NOT NULL COMMENT '组内最近告警时间',
    created_at    DATETIME(6)   NOT NULL COMMENT '建组时间',
    PRIMARY KEY (id),
    KEY idx_alert_group_last_seen (last_seen_at),
    KEY idx_alert_group_tenant (tenant_id),
    KEY idx_alert_group_family (family_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.3.2 误报抑制规则 ============================
CREATE TABLE IF NOT EXISTS t_alert_suppression_rule (
    id          VARCHAR(64)  NOT NULL COMMENT '抑制规则主键（UUID 去连字符）',
    name        VARCHAR(128) NOT NULL COMMENT '规则名',
    pattern     VARCHAR(255) NOT NULL COMMENT '匹配模式（对 familyCode/ruleId/ruleName/metric 不区分大小写包含匹配）',
    family_code VARCHAR(64)  NULL COMMENT '可选：限定作弊家族',
    reason      VARCHAR(512) NULL COMMENT '抑制原因',
    enabled     TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用',
    hit_count   BIGINT       NOT NULL DEFAULT 0 COMMENT '累计命中次数',
    last_hit_at DATETIME(6)  NULL COMMENT '最近命中时间（窗口内降噪统计依据）',
    created_by  VARCHAR(128) NULL COMMENT '创建人',
    created_at  DATETIME(6)  NOT NULL COMMENT '创建时间',
    PRIMARY KEY (id),
    KEY idx_alert_suppression_enabled (enabled)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.3.2 批量通知队列 ============================
CREATE TABLE IF NOT EXISTS t_alert_notify_queue (
    id           VARCHAR(64)   NOT NULL COMMENT '队列项主键（UUID 去连字符）',
    group_id     VARCHAR(64)   NOT NULL COMMENT '关联聚合组',
    priority     VARCHAR(8)    NOT NULL DEFAULT 'P2' COMMENT '优先级',
    channel      VARCHAR(32)   NOT NULL DEFAULT 'dashboard' COMMENT '通知渠道（低优先级为 dashboard-batch）',
    status       VARCHAR(16)   NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / HELD',
    scheduled_at DATETIME(6)   NOT NULL COMMENT '计划发送时间（低优先级延后）',
    sent_at      DATETIME(6)   NULL COMMENT '实际发送时间',
    payload      VARCHAR(2000) NULL COMMENT '通知内容摘要',
    created_at   DATETIME(6)   NOT NULL COMMENT '入队时间',
    PRIMARY KEY (id),
    KEY idx_alert_notify_status_time (status, scheduled_at),
    KEY idx_alert_notify_group (group_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.3.3 自动化规则 ============================
CREATE TABLE IF NOT EXISTS t_automation_rule (
    id            VARCHAR(64)  NOT NULL COMMENT '规则主键（rule_<code 小写>）',
    code          VARCHAR(64)  NOT NULL COMMENT '内置触发条件编码',
    name          VARCHAR(128) NOT NULL COMMENT '规则名',
    trigger_expr  VARCHAR(255) NOT NULL COMMENT '触发条件人类可读表达式（展示用）',
    action_code   VARCHAR(64)  NOT NULL COMMENT '动作编码（对应 AutomationActionHandler#code）',
    threshold     DOUBLE       NOT NULL DEFAULT 0 COMMENT '触发阈值（次数 / 百分比）',
    window_min    INT          NOT NULL DEFAULT 60 COMMENT '观测窗口（分钟）',
    cooldown_min  INT          NOT NULL DEFAULT 30 COMMENT '两次执行最小间隔（分钟），防抖',
    enabled       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用（停用即 no-op）',
    builtin       TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否内置（内置不可删除）',
    last_fired_at DATETIME(6)  NULL COMMENT '最近一次命中并执行时间',
    created_at    DATETIME(6)  NOT NULL COMMENT '创建时间',
    updated_at    DATETIME(6)  NOT NULL COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_automation_rule_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.3.3 自动化状态（真实开关） ============================
CREATE TABLE IF NOT EXISTS t_automation_state (
    state_key   VARCHAR(64)  NOT NULL COMMENT '状态键（degraded_mode / non_critical_detection_enabled / detection_sensitivity ...）',
    state_value VARCHAR(512) NULL COMMENT '状态值（字符串承载 bool / number / version）',
    updated_at  DATETIME(6)  NOT NULL COMMENT '更新时间',
    updated_by  VARCHAR(128) NULL COMMENT '更新者（automation / automation-revert / 管理员）',
    PRIMARY KEY (state_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- ============================ §4.3.3 执行审计与回滚留痕 ============================
CREATE TABLE IF NOT EXISTS t_automation_execution (
    id            BIGINT        AUTO_INCREMENT NOT NULL,
    rule_code     VARCHAR(64)   NOT NULL COMMENT '触发规则编码',
    action_code   VARCHAR(64)   NOT NULL COMMENT '执行动作编码',
    status        VARCHAR(16)   NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS / FAILED / SKIPPED',
    detail        VARCHAR(2000) NULL COMMENT '执行明细（同时作为回滚依据）',
    reversible    TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '是否可回滚',
    reverted      TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否已回滚',
    revert_detail VARCHAR(2000) NULL COMMENT '回滚明细',
    executed_at   DATETIME(6)   NOT NULL COMMENT '执行时间',
    executed_by   VARCHAR(128)  NULL COMMENT '触发者（scheduler / 管理员身份）',
    PRIMARY KEY (id),
    KEY idx_automation_execution_time (executed_at),
    KEY idx_automation_execution_rule (rule_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;