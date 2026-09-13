-- =====================================================================
-- V21: PACC v5.0 补齐阶段1 —— RBAC 三表多对多 / 2FA 可信设备 / 通知渠道
-- 说明：新增权限目录、角色-权限、管理员-角色三张表（多对多权威来源）；
--      为 t_security_totp 追加可信设备列。
--      既有的 t_admin_role.permissions(JSON) 保留作为兼容回退判定。
-- =====================================================================

-- 1. 权限定义目录（细粒度权限键，供 /api/admin/permissions 输出）
CREATE TABLE IF NOT EXISTS t_admin_permission (
    id              VARCHAR(255) NOT NULL,
    permission_key  VARCHAR(128) NOT NULL,
    module          VARCHAR(64)  NOT NULL,
    name            VARCHAR(128) NOT NULL,
    description     VARCHAR(4000),
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_permission_key (permission_key),
    KEY idx_admin_permission_module (module)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 2. 角色-权限关联（与 t_admin_role.id —— roleKey 对应两种均可，统一存 roleKey 便于查询）
CREATE TABLE IF NOT EXISTS t_admin_role_permission (
    id              VARCHAR(255) NOT NULL,
    role_id         VARCHAR(128) NOT NULL,
    permission_id   VARCHAR(128) NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_role_permission (role_id, permission_id),
    KEY idx_role_permission_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3. 管理员-角色多对多（admin_id 为会话身份：飞书 userId 或静态 Key 指纹）
CREATE TABLE IF NOT EXISTS t_admin_user_role (
    id              VARCHAR(255) NOT NULL,
    admin_id        VARCHAR(255) NOT NULL,
    role_id         VARCHAR(128) NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_admin_role (admin_id, role_id),
    KEY idx_admin_user_role_admin (admin_id),
    KEY idx_admin_user_role_role (role_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 4. 2FA 可信设备（JSON：{deviceFp: expiresAtIso}，deviceFp 为哈希存储）
ALTER TABLE t_security_totp ADD COLUMN trusted_devices LONGTEXT;

-- 提醒：以上新增表会在启动时由 DataSeeder / 既有种子逻辑同步填充权限目录。