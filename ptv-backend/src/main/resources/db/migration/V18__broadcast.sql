-- =====================================================================
-- V18: 赛事直播转播配置
-- 说明：管理端维护 OBS 推送的 B 站直播间转播条目；玩家端只读展示 live=true
--      且按 sort 升序/创建倒序的条目。表与实体 @Entity/@Table 严格对应，
--      生产 ddl-auto=validate 校验一致。SQL 只负责建表，不播种。
-- =====================================================================
CREATE TABLE IF NOT EXISTS t_broadcast (
    id               VARCHAR(64)  NOT NULL COMMENT '主键(UUID)',
    title            VARCHAR(128) NOT NULL COMMENT '直播标题',
    bilibili_live_id VARCHAR(32)  NOT NULL COMMENT 'B站直播间号(URL末段)',
    cover_url        VARCHAR(512) COMMENT '封面图地址(可选)',
    description      VARCHAR(512) COMMENT '直播简介',
    platform         VARCHAR(16)  NOT NULL DEFAULT 'bilibili' COMMENT '平台标识',
    live             BIT          NOT NULL DEFAULT FALSE COMMENT '是否对外可见/开播',
    sort             INT          NOT NULL DEFAULT 0 COMMENT '排序权重(小在前)',
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6),
    PRIMARY KEY (id),
    KEY idx_broadcast_live_sort (live, sort)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;