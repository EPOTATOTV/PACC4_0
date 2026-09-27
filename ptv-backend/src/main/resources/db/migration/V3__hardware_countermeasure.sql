-- v4.5 硬件级作弊与完整性对抗：新增设备指纹库表 + 预置已知作弊硬件
-- 生产 MySQL 以此迁移为准；local(H2) 走 ddl-auto=update 自动建表，无需改动。
-- SuspicionFlag.kind 新增 HARDWARE_CHEAT / TAMPERED_INTEGRITY 为字符串枚举，无需 DDL。

CREATE TABLE t_cheat_hardware_profile (
    id VARCHAR(64) NOT NULL PRIMARY KEY,
    vendor VARCHAR(128) NULL,
    vid VARCHAR(16) NULL,
    pid VARCHAR(16) NULL,
    device_class ENUM('USB_HID','PCIE_FPGA','PCIE_DATA_ACQ','UNKNOWN') NOT NULL DEFAULT 'UNKNOWN',
    fingerprint_patterns VARCHAR(4000) NULL,
    cheat_flag ENUM('REASNOW_S1','CRONUS_ZEN','XIM_APEX','TITAN_TWO','DMA_CARD','GENERIC') NOT NULL DEFAULT 'GENERIC',
    risk_score INT NOT NULL DEFAULT 70,
    active TINYINT(1) NOT NULL DEFAULT 1,
    created_by VARCHAR(64) NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 预置已知作弊设备指纹（生产可运营维护）
INSERT INTO t_cheat_hardware_profile
    (id, vendor, vid, pid, device_class, fingerprint_patterns, cheat_flag, risk_score, active, created_by)
VALUES
    ('reasnow_s1', 'ReaSnow', '1a86', '55e0', 'USB_HID', 'reasnow s1,reasnow', 'REASNOW_S1', 75, 1, 'system'),
    ('cronus_zen', 'Cronus', NULL, NULL, 'UNKNOWN', 'cronuszen,cronus zen,zentm', 'CRONUS_ZEN', 80, 1, 'system'),
    ('xim_apex', 'XIM', NULL, NULL, 'UNKNOWN', 'xim apex,xim', 'XIM_APEX', 82, 1, 'system'),
    ('titan_two', 'Titan', '20d0', '1606', 'USB_HID', 'titan two,titan', 'TITAN_TWO', 85, 1, 'system'),
    ('dma_pcie_fpga', NULL, NULL, NULL, 'PCIE_FPGA', NULL, 'DMA_CARD', 92, 1, 'system'),
    ('dma_pcie_dataacq', NULL, NULL, NULL, 'PCIE_DATA_ACQ', NULL, 'DMA_CARD', 90, 1, 'system');

CREATE INDEX idx_cheat_hw_active ON t_cheat_hardware_profile (active, risk_score DESC);