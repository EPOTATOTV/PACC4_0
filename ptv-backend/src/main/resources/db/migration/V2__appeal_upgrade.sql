-- v4.4 误报申诉系统增强：申诉证据快照 + 多级审核流 + 自动初筛
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动补齐，无需改动。

ALTER TABLE t_appeal
    ADD COLUMN review_stage VARCHAR(20) NOT NULL DEFAULT 'auto',
    ADD COLUMN review_role VARCHAR(20) NOT NULL DEFAULT 'sys',
    ADD COLUMN prescreen_score INT NOT NULL DEFAULT 0,
    ADD COLUMN evidence_json VARCHAR(4000) NULL;