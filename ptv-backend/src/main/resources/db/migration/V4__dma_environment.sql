-- v4.5 二期 DMA/IOMMU 环境巡检：新增对抗事件固化表
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动建表，无需改动。

CREATE TABLE t_dma_risk_event (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    pteid VARCHAR(32) NOT NULL,
    iommu_enabled TINYINT(1) NOT NULL DEFAULT 1,
    acpi_dmac_integrity TINYINT(1) NOT NULL DEFAULT 1,
    kernel_debugger_detected TINYINT(1) NOT NULL DEFAULT 0,
    pcie_suspicious TINYINT(1) NOT NULL DEFAULT 0,
    memory_read_alert TINYINT(1) NOT NULL DEFAULT 0,
    antidebug_findings VARCHAR(1000) NULL,
    score INT NOT NULL DEFAULT 0,
    level ENUM('LOW','MEDIUM','HIGH') NOT NULL DEFAULT 'LOW',
    findings VARCHAR(1000) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_dma_event_created ON t_dma_risk_event (created_at DESC);
CREATE INDEX idx_dma_event_pteid ON t_dma_risk_event (pteid, created_at DESC);