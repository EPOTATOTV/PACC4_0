-- v4.8 数据平台与 BI 补全：自定义仪表盘持久化
-- 报表导出（CSV）、实时大屏、数据下钻均为服务端只读聚合，不新增存储。
-- 引擎：MySQL 8.0（utf8mb4）；H2 local 模式同样兼容（MODE=MySQL）

CREATE TABLE t_bi_dashboard (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'platform',
    name         VARCHAR(128) NOT NULL,
    widgets      VARCHAR(4000) NOT NULL COMMENT '仪表盘组件定义（JSON：图表类型/数据源/筛选/刷新频率）',
    layout       VARCHAR(255),
    created_by   VARCHAR(64),
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_bi_dashboard_tenant ON t_bi_dashboard (tenant_id);