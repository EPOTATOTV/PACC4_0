-- v5.0 P2：动效配置中心与红屏动效模板。
-- 单一默认行（id='default'）：motion_level 控制全局动效档位（off/gentle/standard/strong），
-- effects_json/redscreen_json 承载各类动效开关与红屏模板参数，供管理端编辑、客户端下发。

CREATE TABLE IF NOT EXISTS t_effect_config (
    id                 VARCHAR(32)  NOT NULL,
    motion_level       VARCHAR(16)  NOT NULL DEFAULT 'standard',
    effects_json       VARCHAR(4000) NOT NULL DEFAULT '{}',
    redscreen_template VARCHAR(16)  NOT NULL DEFAULT 'standard',
    redscreen_json     VARCHAR(4000) NOT NULL DEFAULT '{}',
    updated_by         VARCHAR(128),
    updated_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

INSERT INTO t_effect_config
  (id, motion_level, effects_json, redscreen_template, redscreen_json, updated_by, updated_at)
VALUES
  ('default', 'standard',
   '{"page_transition":true,"counter":true,"table_reveal":true,"modal_reveal":true,"data_stream":true,"cursor_glow":true}',
   'standard',
   '{"flash_count":4,"expand":true,"scanline":true,"glitch_text":true,"shake":false}',
   'system', CURRENT_TIMESTAMP(6));