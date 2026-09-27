-- v5.1 P0-2.2：补全下载站发布物的校验和与体积。
--
-- 背景：V24 种子里 size_bytes 全为 0，校验和只有 Windows 客户端与 JVM 探针是真实值，
-- 另外四个平台（LNX / APK / IOS / HMY）的安装包尚未产出，保持全零占位。
--
-- 这里只回填「确实存在于 deploy/dl-web/files/ 的产物」——数值由
--   deploy/dl-web/compute-sha256.sh
-- 实际计算得到，不编造。全零行在前端显示为「尚未发布」，不会展示假校验和。
--
-- 后续发行新版本时：先跑该脚本拿到新产物的 sha256 / size_bytes，再追加迁移或由管控台写入，
-- 不要直接改本文件（Flyway 校验和不可变）。

-- Windows 客户端：pacc-client-windows-x64-v5.0.0.zip
UPDATE t_dl_release
   SET sha256     = 'cc82736ce9bdf68859fc6dd18d21da8e0f51b75a4f12ac1f2e0d614ad257df41',
       size_bytes = 65693753,
       updated_at = CURRENT_TIMESTAMP(6)
 WHERE id = 'rel-win-client';

-- JVM 探针：ptv-agent-5.0.0.jar
UPDATE t_dl_release
   SET sha256     = 'c86602595228e17835654639132d73297dcd7ec65ef3302995089e292705557e',
       size_bytes = 100347,
       updated_at = CURRENT_TIMESTAMP(6)
 WHERE id = 'rel-jvm-probe';