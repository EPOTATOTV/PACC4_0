-- v4.7 特征库热更新：特征版本化，支持增量 diff 与失败自动回滚。
-- version 从 1 起每经历一次状态/内容变更自增；updated_at 记录最近一次变更时间。
ALTER TABLE t_signature
    ADD COLUMN version BIGINT NOT NULL DEFAULT 1,
    ADD COLUMN updated_at TIMESTAMP NULL;