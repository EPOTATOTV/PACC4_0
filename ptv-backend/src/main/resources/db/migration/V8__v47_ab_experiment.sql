-- v4.7 检测算法 A/B 测试框架：实验表
CREATE TABLE t_ab_experiment (
    id                       VARCHAR(64)  NOT NULL,
    name                     VARCHAR(128) NOT NULL,
    description              VARCHAR(512) NULL,
    dimension                VARCHAR(32)  NOT NULL,
    variant_a                VARCHAR(256) NOT NULL,
    variant_b                VARCHAR(256) NOT NULL,
    target_percent           INT          NOT NULL DEFAULT 50,
    status                   VARCHAR(16)  NOT NULL DEFAULT 'DRAFT',
    started_at               DATETIME(6)  NULL,
    ended_at                 DATETIME(6)  NULL,
    metrics_ct_exposure      BIGINT       NOT NULL DEFAULT 0,
    metrics_ct_detect        BIGINT       NOT NULL DEFAULT 0,
    metrics_ct_false_positive BIGINT      NOT NULL DEFAULT 0,
    winner                   VARCHAR(256) NULL,
    created_at               DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_ab_experiment_status ON t_ab_experiment (status);