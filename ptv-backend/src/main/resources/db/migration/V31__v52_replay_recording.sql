-- v5.2 §7.3 查端回放录像元数据（密文落盘，密钥随元数据存库，保留 30 天）
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动补齐。

CREATE TABLE t_replay_recording (
    id             VARCHAR(64)  NOT NULL,
    alert_id       VARCHAR(64)  NOT NULL DEFAULT '',
    pteid          VARCHAR(64)  NOT NULL DEFAULT '',
    storage_path   VARCHAR(255) NOT NULL DEFAULT '',
    frames         INT          NOT NULL DEFAULT 0,
    width          INT          NOT NULL DEFAULT 0,
    height         INT          NOT NULL DEFAULT 0,
    fps            INT          NOT NULL DEFAULT 0,
    duration_ms    BIGINT       NOT NULL DEFAULT 0,
    size_bytes     BIGINT       NOT NULL DEFAULT 0,
    plain_size     BIGINT       NOT NULL DEFAULT 0,
    sha256         CHAR(64)     NOT NULL DEFAULT '',
    format         VARCHAR(16)  NOT NULL DEFAULT 'MJPEG-AVI',
    enc_key        VARCHAR(128) NOT NULL DEFAULT '',
    enc_iv         VARCHAR(64)  NOT NULL DEFAULT '',
    created_at     DATETIME(6)  NOT NULL,
    expires_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_replay_pteid (pteid),
    KEY idx_replay_alert (alert_id),
    KEY idx_replay_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;