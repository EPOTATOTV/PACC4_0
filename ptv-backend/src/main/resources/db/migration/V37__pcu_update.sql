-- =====================================================================
-- V37: PCU（跨平台更新模块）服务端落表 / 扩列（设计文档 §4.11）
-- 覆盖：t_release 补全量包签名与差分包元数据；新增 t_update_report 更新结果上报。
--
-- 契约背景：端侧 GET /v1/update/check 按 platform×channel 取最新已发布版本，
-- POST /v1/update/report 上报更新结果。本迁移只做存储，不引入任何封禁语义。
--
-- 兼容性：生产 MySQL 8.0（utf8mb4 / InnoDB）；本地 H2（MODE=MySQL）同样执行。
--   新列一律带默认值或可空，既有 t_release 行不受影响（file_size / delta_size 回填 0）。
--   已合入的迁移脚本不可改，后续变更追加新版本号。
-- =====================================================================

-- 1. t_release 扩列：全量包体积 / RSA-2048 签名 / 差分包元数据
ALTER TABLE t_release ADD COLUMN file_size BIGINT NOT NULL DEFAULT 0 COMMENT '全量包字节数';
ALTER TABLE t_release ADD COLUMN signature VARCHAR(1024) NULL COMMENT '全量包 RSA-2048 签名（Base64）';
ALTER TABLE t_release ADD COLUMN delta_from_version VARCHAR(48) NULL COMMENT '差分包基线版本（与端侧 current_version 精确相等才下发）';
ALTER TABLE t_release ADD COLUMN delta_url VARCHAR(500) NULL COMMENT '差分包下载地址';
ALTER TABLE t_release ADD COLUMN delta_sha256 VARCHAR(128) NULL COMMENT '差分包 SHA-256（十六进制，不带 sha256: 前缀）';
ALTER TABLE t_release ADD COLUMN delta_size BIGINT NOT NULL DEFAULT 0 COMMENT '差分包字节数';

-- 2. t_update_report：端侧更新结果上报，供管理端统计成功率 / 失败率与回滚
--    pteid 只作统计口径，允许为空（未登录即可上报）；error_message 端侧已截断，服务端再兜底到 500。
CREATE TABLE IF NOT EXISTS t_update_report (
    id            VARCHAR(36)  NOT NULL COMMENT '上报记录主键（UUID）',
    pteid         VARCHAR(64)  NULL COMMENT '玩家/设备标识，仅用于统计，可空',
    platform      VARCHAR(16)  NULL COMMENT 'windows / android / ios / harmony / linux / macos',
    from_version  VARCHAR(48)  NULL COMMENT '更新前版本',
    to_version    VARCHAR(48)  NULL COMMENT '目标版本',
    status        VARCHAR(24)  NOT NULL DEFAULT 'failed' COMMENT 'success / failed / rolled_back / skipped',
    error_message VARCHAR(500) NULL COMMENT '失败原因（超长截断，最多 500 字）',
    created_at    DATETIME(6)  NOT NULL COMMENT '上报时间',
    PRIMARY KEY (id),
    KEY idx_update_report_platform_created (platform, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;