-- v4.8 合规与安全审计强化 + 多租户架构（平台租户层）
-- 引擎：MySQL 8.0（utf8mb4）；H2 local 模式同样兼容（MODE=MySQL）

-- 1) 管理员操作审计：记录谁/何时/操作了什么/结果/IP
CREATE TABLE t_admin_operation_log (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    actor        VARCHAR(128) NOT NULL DEFAULT 'system',
    role         VARCHAR(32),
    action       VARCHAR(64)  NOT NULL,
    entity_type  VARCHAR(64),
    entity_id    VARCHAR(128),
    detail       VARCHAR(4000),
    ip           VARCHAR(64),
    http_method  VARCHAR(8),
    http_path    VARCHAR(255),
    http_status  INT NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_admin_op_log_time ON t_admin_operation_log (created_at);
CREATE INDEX idx_admin_op_log_actor ON t_admin_operation_log (actor);
CREATE INDEX idx_admin_op_log_action ON t_admin_operation_log (action);

-- 2) 平台租户：多租户分级（免费/专业/企业）
CREATE TABLE t_tenant (
    tenant_id    VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    plan         VARCHAR(16)  NOT NULL DEFAULT 'FREE',
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    max_admins   INT NOT NULL DEFAULT 1,
    data_retention_days INT NOT NULL DEFAULT 30,
    api_access   TINYINT(1) NOT NULL DEFAULT 0,
    webhook_access TINYINT(1) NOT NULL DEFAULT 0,
    custom_policy TINYINT(1) NOT NULL DEFAULT 0,
    sla_description VARCHAR(255),
    note         VARCHAR(512),
    created_at   DATETIME(6) NOT NULL,
    created_by   VARCHAR(64),
    PRIMARY KEY (tenant_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3) 平台租户管理员：绑定租户的管理员身份
CREATE TABLE t_tenant_admin (
    id             BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id      VARCHAR(64)  NOT NULL,
    admin_identity VARCHAR(128) NOT NULL,
    role           VARCHAR(32)  NOT NULL DEFAULT 'admin',
    enabled        TINYINT(1) NOT NULL DEFAULT 1,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_tenant_admin UNIQUE (tenant_id, admin_identity)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_tenant_admin_tenant ON t_tenant_admin (tenant_id);