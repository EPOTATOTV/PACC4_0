package com.potatotv.pacc.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * v4.5 硬件级作弊设备指纹库（服务端判定源）。
 * <p>记录了已知作弊硬件的 VID/PID、设备类、设备字符串指纹与风险分，
 * 供 {@code HardwareFingerprintService} 在玩家端上报外设后匹配判定。</p>
 * <p>覆盖 DMA 采集卡（PCIE_FPGA/DATA_ACQ）、鼠标宏/手柄模拟器（ReaSnow S1、
 * CronusZen、XIM Apex、Titan Two）等已知作弊设备。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_cheat_hardware_profile")
public class CheatHardwareProfile {

    public enum CheatFlag {
        REASNOW_S1,    // ReaSnow S1 手柄转换器/宏
        CRONUS_ZEN,    // CronusZen
        XIM_APEX,      // XIM Apex
        TITAN_TWO,     // Titan Two
        DMA_CARD,      // PCIe DMA 采集卡（FPGA / 数据采集）
        GENERIC
    }

    public enum DeviceClass {
        USB_HID,       // USB 输入设备（键盘/鼠标/手柄）
        PCIE_FPGA,     // PCIe FPGA 设备（DMA）
        PCIE_DATA_ACQ, // PCIe 数据采集卡（DMA）
        UNKNOWN
    }

    @Id
    private String id;

    private String vendor;

    /** VID（可空：用于覆盖未知 PCIe/数据采集卡类设备）。 */
    private String vid;

    private String pid;

    @Enumerated(EnumType.STRING)
    private DeviceClass deviceClass;

    /** 设备字符串指纹（前缀/子串匹配，逗号分隔多模式）。 */
    private String fingerprintPatterns;

    @Enumerated(EnumType.STRING)
    private CheatFlag cheatFlag;

    /** 命中该设备的风险分（0-100），进融合评分。 */
    private int riskScore;

    @Builder.Default
    private boolean active = true;

    private String createdBy;

    @Builder.Default
    private Instant createdAt = Instant.now();
}