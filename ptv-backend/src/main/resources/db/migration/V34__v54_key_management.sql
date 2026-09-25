-- v5.4 §5 密钥管理：根密钥派生托管密钥（HKDF-SHA256）+ 防篡改审计链。
-- 设计意图：根密钥永不出服务器，设备/服务子密钥一律「派生」而非落明文——
-- 库内只存派生盐 + 指纹，数据库被拖库也拿不到可用密钥；
-- 生命周期 ACTIVE → ROTATED（仅为历史密文可解而保留）→ EXPIRED，或异常时 → REVOKED；
-- 每次状态迁移写入 t_key_audit_log，按 seq 组织哈希链（prev_hash/hash），可复核不可静默改写。
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动补齐。
-- 注：本文件里 salt / fingerprint / prev_hash / hash 用 VARCHAR(64) 而非 CHAR(64)。
-- 生产用 ddl-auto=validate，Hibernate 会逐列比对 JDBC 类型码（VARCHAR 12 vs CHAR 1），
-- 与实体的 length = 64 对不上会在启动时直接报 schema 校验失败；而且 CHAR 的右侧补位
-- 在 H2/MySQL 上读回行为不一致，哈希链比对还得处处 trim。既无定长收益，就不引入。

CREATE TABLE t_managed_key (
    id            VARCHAR(64)  NOT NULL,
    key_id        VARCHAR(80)  NOT NULL COMMENT '人类可读稳定标识（purpose-vN）',
    purpose       VARCHAR(32)  NOT NULL COMMENT 'CONFIG_ENCRYPT/REPORT_ENCRYPT/MODEL_SIGN/ATTESTATION/DB_ENCRYPT/API_SIGN/WEBHOOK_SIGN',
    algorithm     VARCHAR(32)  NOT NULL DEFAULT 'HKDF-SHA256',
    state         VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/ROTATED/EXPIRED/REVOKED',
    version       INT          NOT NULL DEFAULT 1 COMMENT '同用途内的轮换版本号',
    derived_from  VARCHAR(64)  NOT NULL DEFAULT 'ROOT' COMMENT '派生来源（当前恒为根密钥 ROOT）',
    salt          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '派生盐（32 字节随机数的十六进制，非密）',
    fingerprint   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '派生密钥指纹 sha256(hex(key))，不存明文密钥',
    created_by    VARCHAR(64)  NOT NULL DEFAULT '',
    note          VARCHAR(255) NOT NULL DEFAULT '',
    created_at    DATETIME(6)  NOT NULL,
    activated_at  DATETIME(6)  NULL,
    rotated_at    DATETIME(6)  NULL,
    expires_at    DATETIME(6)  NULL COMMENT '轮换到期时间（默认 90 天）',
    revoked_at    DATETIME(6)  NULL,
    revoke_reason VARCHAR(255) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_managed_key_id (key_id),
    KEY idx_managed_key_purpose (purpose, state),
    KEY idx_managed_key_expires (expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE t_key_audit_log (
    id         VARCHAR(64)  NOT NULL,
    seq        BIGINT       NOT NULL COMMENT '单调递增链序号（用于校验连续性与顺序）',
    key_id     VARCHAR(80)  NOT NULL DEFAULT '',
    action     VARCHAR(24)  NOT NULL COMMENT 'CREATE/ACTIVATE/ROTATE/REVOKE/EXPIRE/DEACTIVATE',
    from_state VARCHAR(16)  NOT NULL DEFAULT '',
    to_state   VARCHAR(16)  NOT NULL DEFAULT '',
    operator   VARCHAR(64)  NOT NULL DEFAULT '',
    note       VARCHAR(255) NOT NULL DEFAULT '',
    created_at DATETIME(6)  NOT NULL,
    prev_hash  VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '上一条的 hash（创世行为空串）',
    hash       VARCHAR(64)  NOT NULL COMMENT '本条内容哈希，串联成防篡改链',
    PRIMARY KEY (id),
    UNIQUE KEY uk_key_audit_seq (seq),
    KEY idx_key_audit_key (key_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;