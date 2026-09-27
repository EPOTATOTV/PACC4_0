-- v5.2 §7.4 申诉 AI 自动复核：记录自动复核结论（PENDING / MISREPORT / CONFIRMED / INCONCLUSIVE）
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动补齐。

ALTER TABLE t_appeal
    ADD COLUMN auto_review VARCHAR(16) NOT NULL DEFAULT 'PENDING';