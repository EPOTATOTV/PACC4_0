-- v5.4 §3.6 客户端安全事件 / 远程证明（反篡改）。三张表：
--   t_security_event    客户端上报的安全事件，按 seq 串成哈希链（防事后删改）
--   t_attestation_record 远程证明挑战应答结果（成功与失败都落库，用于通过率与排查）
--   t_known_good_hash   已知合法代码段/配置/JAR 摘要注册表（服务端独立核验，不信任端上报）
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动补齐。不使用 JSON 列与分区。

CREATE TABLE t_security_event (
    id           VARCHAR(64)  NOT NULL,
    seq          BIGINT       NOT NULL,
    pteid        VARCHAR(64)  NOT NULL DEFAULT '',
    platform     VARCHAR(16)  NOT NULL DEFAULT '',
    client_ver   VARCHAR(32)  NOT NULL DEFAULT '',
    event_type   VARCHAR(48)  NOT NULL,
    level        VARCHAR(16)  NOT NULL,
    detail       VARCHAR(512) NOT NULL DEFAULT '',
    evidence     LONGTEXT     NULL,
    occurred_at  DATETIME(6)  NOT NULL,
    received_at  DATETIME(6)  NOT NULL,
    prev_hash    CHAR(64)     NOT NULL DEFAULT '',
    hash         CHAR(64)     NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_security_event_seq (seq),
    KEY idx_security_event_type (event_type, received_at),
    KEY idx_security_event_pteid (pteid, received_at),
    KEY idx_security_event_level (level, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_attestation_record (
    id            VARCHAR(64)  NOT NULL,
    challenge_id  VARCHAR(64)  NOT NULL,
    pteid         VARCHAR(64)  NOT NULL DEFAULT '',
    platform      VARCHAR(16)  NOT NULL DEFAULT '',
    client_ver    VARCHAR(32)  NOT NULL DEFAULT '',
    nonce         VARCHAR(64)  NOT NULL DEFAULT '',
    code_hash     CHAR(64)     NOT NULL DEFAULT '',
    config_hash   CHAR(64)     NOT NULL DEFAULT '',
    runtime_state LONGTEXT     NULL,
    status        VARCHAR(16)  NOT NULL,
    reason        VARCHAR(64)  NOT NULL DEFAULT '',
    elapsed_ms    BIGINT       NOT NULL DEFAULT 0,
    issued_at     DATETIME(6)  NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_attest_pteid (pteid, created_at),
    KEY idx_attest_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_known_good_hash (
    id         VARCHAR(64)  NOT NULL,
    label      VARCHAR(128) NOT NULL,
    kind       VARCHAR(24)  NOT NULL,
    hash       CHAR(64)     NOT NULL,
    active     TINYINT(1)   NOT NULL DEFAULT 1,
    created_by VARCHAR(64)  NOT NULL DEFAULT '',
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_known_hash (kind, hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;