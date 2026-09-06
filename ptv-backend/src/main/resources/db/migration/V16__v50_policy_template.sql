-- v5.0 生态：策略模板（场景一键应用）+ 插件规则沙箱校验
-- 引擎：MySQL 8.0（utf8mb4）；H2 local 模式同样兼容（MODE=MySQL）

CREATE TABLE t_policy_template (
    template_id   VARCHAR(64)  NOT NULL,
    name          VARCHAR(128) NOT NULL,
    scene         VARCHAR(32)  NOT NULL COMMENT 'PVP / PVE / CREATIVE / MINI_GAME / LIVE',
    description   VARCHAR(512),
    actions_json  TEXT         NOT NULL COMMENT '处置项 JSON 数组：[{scopeType,scopeValue,action,severity,note}]',
    created_by    VARCHAR(64),
    created_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (template_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE INDEX idx_policy_template_scene ON t_policy_template (scene);

-- 内建场景模板（占位示例）：一键应用 PVP / PVE 处置策略
INSERT INTO t_policy_template (template_id, name, scene, description, actions_json, created_by, created_at) VALUES
('tpl_pvp_default', 'PVP 竞技默认策略', 'PVP', '竞技对局从严处置：高威胁家族直接阻断，中等样本隔离观察',
 '[{"scopeType":"FAMILY","scopeValue":"killaura_*","action":"BLOCK","severity":4,"note":"自瞄/杀戮光环家族"},{"scopeType":"FAMILY","scopeValue":"auto_clicker_*","action":"ISOLATE","severity":3,"note":"连点家族"},{"scopeType":"SAMPLE","scopeValue":"aim_smooth_1","action":"ISOLATE","severity":3,"note":"平滑自瞄样本"}]',
 'system', NOW()),
('tpl_pve_default', 'PVE 休闲默认策略', 'PVE', '休闲玩法以观察为主，仅对确定性样本阻断',
 '[{"scopeType":"FAMILY","scopeValue":"killaura_*","action":"ISOLATE","severity":3,"note":"自瞄家族"},{"scopeType":"SIGNATURE","scopeValue":"nbt_exploit","action":"BLOCK","severity":4,"note":"NBT 漏洞特征"}]',
 'system', NOW());