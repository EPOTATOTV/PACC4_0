-- v4.6 检测能力深化：零日外挂检测发现表 + 威胁情报样本表
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动建表。

CREATE TABLE t_zero_day_finding (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    pteid VARCHAR(32) NOT NULL,
    edition VARCHAR(16) NOT NULL DEFAULT 'JAVA',
    iso_score DOUBLE NOT NULL DEFAULT 0,
    recon_error DOUBLE NOT NULL DEFAULT 0,
    baseline_deviation DOUBLE NOT NULL DEFAULT 0,
    composite_score INT NOT NULL DEFAULT 0,
    confidence_tier VARCHAR(16) NOT NULL DEFAULT 'LOW',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
    features_json CLOB NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP NULL,
    reviewer VARCHAR(64) NULL,
    review_comment VARCHAR(1000) NULL,
    confirmed TINYINT(1) NULL
);

CREATE INDEX idx_zd_pteid ON t_zero_day_finding (pteid);
CREATE INDEX idx_zd_status ON t_zero_day_finding (status);
CREATE INDEX idx_zd_created ON t_zero_day_finding (created_at DESC);

CREATE TABLE t_threat_intel_sample (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    pteid VARCHAR(32) NOT NULL,
    edition VARCHAR(16) NOT NULL DEFAULT 'JAVA',
    md5 VARCHAR(32) NULL,
    sha1 VARCHAR(40) NULL,
    family VARCHAR(64) NULL,
    static_dims CLOB NULL,
    generated_rule CLOB NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'NEW',
    confirmed TINYINT(1) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    reviewed_at TIMESTAMP NULL,
    reviewer VARCHAR(64) NULL
);

CREATE INDEX idx_ti_created ON t_threat_intel_sample (created_at DESC);
CREATE INDEX idx_ti_family ON t_threat_intel_sample (family);