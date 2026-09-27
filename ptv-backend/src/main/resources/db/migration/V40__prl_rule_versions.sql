-- =====================================================================
-- V40: PRL 规则版本库（设计文档 §2.13.1 / §3.3）
-- 覆盖：prl_rule_versions 规则版本表；prl_rule_rollout 灰度比例表。
--
-- 契约背景：PRL 引擎自带 RuleVersionStore 接口，但刻意不实现它 —— 引擎是零依赖独立库，
--   不能自带数据库。表结构因此由管控后端定义，这里是唯一一处落表的地方。
--   字段与 com.potatotv.prl.engine.RuleVersion 一一对应，少一个都会让版本记录读不回来。
--
-- 兼容性：生产 MySQL 8.0（utf8mb4 / InnoDB）；本地 H2（MODE=MySQL）同样执行。
--   两张表都是新建，不触碰既有数据。已合入的迁移脚本不可改，后续变更追加新版本号。
-- =====================================================================

-- 1. prl_rule_versions：规则版本。主键用（规则名, 版本号），与 RuleVersionStore.find 的入参一致，
--    天然挡住「同一版本号存两次不同内容」——那正是管理端最容易出的事故。
CREATE TABLE IF NOT EXISTS prl_rule_versions (
    rule_name   VARCHAR(64)  NOT NULL COMMENT '规则名（必须与源码里 rule 后的名字一致）',
    version     VARCHAR(32)  NOT NULL COMMENT '语义化版本号，如 1.0.0',
    source      MEDIUMTEXT   NOT NULL COMMENT 'PRL 源码',
    bytecode    LONGBLOB     NOT NULL COMMENT '编译产物 .prlc 字节；发布时直接装进引擎，不再重编译',
    checksum    CHAR(64)     NOT NULL COMMENT '源码 SHA-256（十六进制），用于识别同版本号换内容',
    author      VARCHAR(64)  NULL COMMENT '提交人（取管理端登录身份）',
    status      VARCHAR(16)  NOT NULL COMMENT 'draft / testing / active / deprecated / disabled',
    created_at  DATETIME(6)  NOT NULL COMMENT '提交时间',
    approved_by VARCHAR(64)  NULL COMMENT '审批人；draft/testing 阶段为空',
    rollback_to VARCHAR(32)  NULL COMMENT '回滚目标版本号；发布时回填上一个 active',
    PRIMARY KEY (rule_name, version),
    KEY idx_prl_rule_versions_status (rule_name, status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 2. prl_rule_rollout：灰度比例。单独一张表而不是 prl_rule_versions 的一列，因为有灰度的
--    是「规则」而不是「版本」——同一时刻一条规则只有一个生效版本，比例属于那条规则。
--    缺行视为 100（全量），这样新发布的规则不需要先写一行才敢下发。
CREATE TABLE IF NOT EXISTS prl_rule_rollout (
    rule_name  VARCHAR(64) NOT NULL COMMENT '规则名',
    percent    INT NOT NULL DEFAULT 100 COMMENT '灰度比例 0-100；100 表示全量',
    updated_at DATETIME(6) NOT NULL COMMENT '最近一次调整时间',
    PRIMARY KEY (rule_name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;