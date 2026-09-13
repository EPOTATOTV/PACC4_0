-- v5.0 P1：检测器配置中心。以“检测器键值 + 开关 + 阈值/参数 JSON”承载灰度与调参。
CREATE TABLE IF NOT EXISTS t_detector_config (
    id            VARCHAR(255) NOT NULL,
    detector_key  VARCHAR(128) NOT NULL,
    name          VARCHAR(128) NOT NULL,
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    meta_json     VARCHAR(4000),
    updated_by    VARCHAR(128),
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_detector_config_key (detector_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;