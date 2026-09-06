-- v4.6 威胁情报深化：样本自动分析与 AI 家族聚类新增列
ALTER TABLE t_threat_intel_sample
    ADD COLUMN family_label VARCHAR(64) NULL,
    ADD COLUMN auto_analysis CLOB NULL;

CREATE INDEX idx_ti_family_label ON t_threat_intel_sample (family_label);