-- v5.0 生态：插件市场（注册 / 审核工作流 / 评分评论 / 下载统计 / 签名）
-- 引擎：MySQL 8.0（utf8mb4）；H2 local 模式同样兼容（MODE=MySQL）

-- 1) 插件
CREATE TABLE t_plugin (
    plugin_id        VARCHAR(64)  NOT NULL,
    name             VARCHAR(128) NOT NULL,
    description      VARCHAR(1000),
    type             ENUM('DETECTION_RULE','POLICY_TEMPLATE','SERVER_INTEGRATION','NOTIFICATION','DATA_ANALYTIC') NOT NULL COMMENT '插件类型枚举',
    author           VARCHAR(64)  NOT NULL,
    plugin_version   VARCHAR(32)  NOT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PENDING_REVIEW / PUBLISHED / REJECTED / REMOVED',
    signature_sha256 VARCHAR(64),
    package_url      VARCHAR(255),
    downloads        BIGINT       NOT NULL DEFAULT 0,
    rating_count     INT          NOT NULL DEFAULT 0,
    rating_sum       INT          NOT NULL DEFAULT 0,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    published_at     DATETIME(6)  NULL,
    PRIMARY KEY (plugin_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_plugin_status ON t_plugin (status);
CREATE INDEX idx_plugin_type   ON t_plugin (type);

-- 2) 插件审核记录（管理员操作，完整留痕）
CREATE TABLE t_plugin_review (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    plugin_id  VARCHAR(64) NOT NULL,
    reviewer   VARCHAR(64) NOT NULL,
    action     VARCHAR(16) NOT NULL COMMENT 'APPROVED / REJECTED / REMOVED',
    comment    VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_plugin_review_plugin ON t_plugin_review (plugin_id);

-- 3) 插件评分评论
CREATE TABLE t_plugin_comment (
    id         BIGINT NOT NULL AUTO_INCREMENT,
    plugin_id  VARCHAR(64) NOT NULL,
    author     VARCHAR(64) NOT NULL,
    rating     INT NOT NULL,
    content    VARCHAR(1000),
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_plugin_comment_plugin ON t_plugin_comment (plugin_id);