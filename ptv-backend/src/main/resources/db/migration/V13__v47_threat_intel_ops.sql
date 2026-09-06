-- ============================================================
-- v4.7 威胁情报运营中台：主动威慑分级处置 + IOC 中心化
-- 家族档案/谱系图为实时聚合 ThreatIntelSample，不建新表。
-- ============================================================

-- 主动威慑：对已确认恶意样本/家族/特征设置分级处置策略
CREATE TABLE IF NOT EXISTS t_deter_policy (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    scope_type   VARCHAR(16)  NOT NULL,   -- FAMILY / SAMPLE / SIGNATURE
    scope_value  VARCHAR(128) NOT NULL,   -- familyLabel / sampleId / 特征码名
    action       VARCHAR(16)  NOT NULL,   -- MONITOR / BLOCK / ISOLATE / IGNORE
    severity     INT          NOT NULL DEFAULT 3,  -- 1-5
    enabled      TINYINT(1)   NOT NULL DEFAULT 1,
    note         VARCHAR(512),
    created_by   VARCHAR(64),
    created_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_deter_scope UNIQUE (scope_type, scope_value)
);

-- IOC 中心化：从样本自动分析/静态指纹抽取的可观测指标
CREATE TABLE IF NOT EXISTS t_ioc_indicator (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    value          VARCHAR(255) NOT NULL,
    type           VARCHAR(16)  NOT NULL,   -- FILE_HASH / STRING / IP / URL / CLIENT_FAMILY
    source_id      VARCHAR(64),
    source_family  VARCHAR(128),
    severity       INT  NOT NULL DEFAULT 3,
    state          VARCHAR(16)  NOT NULL DEFAULT 'OPEN',  -- OPEN / DISARMED / EXPIRED
    subscribed     TINYINT(1)   NOT NULL DEFAULT 0,
    alert_threshold INT NOT NULL DEFAULT 3,
    hit_count      INT  NOT NULL DEFAULT 0,
    first_seen     DATETIME(6)  NOT NULL,
    last_seen      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_ioc_value (value),
    INDEX idx_ioc_type (type),
    INDEX idx_ioc_state (state)
);