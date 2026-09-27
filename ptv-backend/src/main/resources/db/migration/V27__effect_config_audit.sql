-- v5.1 P1-3.10：动效配置变更审计。
-- 动效档位直接影响客户端与下载站的观感，改了什么、谁改的、什么时候改的要能回溯。
-- 只追加不修改：每次 PUT /api/admin/effect 落一行，保留变更后的完整快照。

CREATE TABLE IF NOT EXISTS t_effect_config_audit (
    id                 BIGINT AUTO_INCREMENT NOT NULL,
    changed_by         VARCHAR(64)   NOT NULL COMMENT '操作人（X-Admin-Key 主体或管理端账号）',
    motion_level       VARCHAR(16)   NOT NULL COMMENT '变更后的全局动效档位',
    redscreen_template VARCHAR(16)   NOT NULL COMMENT '变更后的红屏模板',
    summary            VARCHAR(512)  NOT NULL COMMENT '人类可读的差异摘要',
    effects_json       VARCHAR(4000) NOT NULL COMMENT '变更后的动效开关快照',
    redscreen_json     VARCHAR(4000) NOT NULL COMMENT '变更后的红屏参数快照',
    created_at         DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    KEY idx_effect_audit_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;