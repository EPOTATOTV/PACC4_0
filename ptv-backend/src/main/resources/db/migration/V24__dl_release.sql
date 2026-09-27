-- v5.0 P2：下载站发布物与下载统计。
-- t_dl_release     每个平台/产物一份当前公映版本（url / sha256 / 体积），供 /api/dl/latest 下发。
-- t_dl_download_stat 按“天 × 平台 × 产物”聚合下载计数，供管理端看趋势；track 由端上信标累加。

CREATE TABLE IF NOT EXISTS t_dl_release (
    id          VARCHAR(64)  NOT NULL,
    platform    VARCHAR(16)  NOT NULL COMMENT 'WIN/LNX/APK/IOS/HMY/JVM',
    artifact    VARCHAR(48)  NOT NULL COMMENT 'client / probe',
    version     VARCHAR(32)  NOT NULL,
    file_url    VARCHAR(512) NOT NULL,
    sha256      VARCHAR(64) NOT NULL,
    size_bytes  BIGINT       NOT NULL DEFAULT 0,
    enabled     TINYINT(1)   NOT NULL DEFAULT 1,
    updated_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dl_release_pa_ver (platform, artifact, version)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

CREATE TABLE IF NOT EXISTS t_dl_download_stat (
    id        BIGINT AUTO_INCREMENT NOT NULL,
    day_date  DATE   NOT NULL,
    platform  VARCHAR(16) NOT NULL,
    artifact  VARCHAR(48) NOT NULL,
    count     BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_dl_stat_day_pa (day_date, platform, artifact)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 种子：当前公开发布物（与 files/version.json 对齐；四种移动/桌面平台 sha 待发行填零占位）
INSERT INTO t_dl_release (id, platform, artifact, version, file_url, sha256, size_bytes, enabled, updated_at) VALUES
('rel-win-client' , 'WIN', 'client', 'v5.0.0', '/files/pacc-client-windows-x64-v5.0.0.zip',
 'cc82736ce9bdf68859fc6dd18d21da8e0f51b75a4f12ac1f2e0d614ad257df41', 0, 1, CURRENT_TIMESTAMP(6)),
('rel-lnx-client' , 'LNX', 'client', 'v5.0.0', '/files/pacc-client-linux-x86_64-v5.0.0.tar.gz',
 '0000000000000000000000000000000000000000000000000000000000000000', 0, 1, CURRENT_TIMESTAMP(6)),
('rel-apk-client' , 'APK', 'client', 'v5.0.0', '/files/pacc-client-android-v5.0.0.apk',
 '0000000000000000000000000000000000000000000000000000000000000000', 0, 1, CURRENT_TIMESTAMP(6)),
('rel-ios-client' , 'IOS', 'client', 'v5.0.0', '/files/pacc-client-ios-v5.0.0.tar.gz',
 '0000000000000000000000000000000000000000000000000000000000000000', 0, 1, CURRENT_TIMESTAMP(6)),
('rel-hmy-client' , 'HMY', 'client', 'v5.0.0', '/files/pacc-client-harmony-v5.0.0.tar.gz',
 '0000000000000000000000000000000000000000000000000000000000000000', 0, 1, CURRENT_TIMESTAMP(6)),
('rel-jvm-probe'  , 'JVM', 'probe', 'v5.0.0', '/files/ptv-agent-5.0.0.jar',
 'c86602595228e17835654639132d73297dcd7ec65ef3302995089e292705557e', 0, 1, CURRENT_TIMESTAMP(6));