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
 * v4.5 DMA/IOMMU 环境巡检与对抗事件固化（服务端判定+审计）。
 * <p>玩家端上报 DMA 设备、IOMMU 状态、ACPI 完整性、内核调试器/反调试矩阵等环境事实，
 * 服务端评分后按 PTEID 落库，供管理端查看近期事件与按账号聚合告警。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_dma_risk_event")
public class DmaRiskEvent {

    public enum Level {
        LOW, MEDIUM, HIGH
    }

    @Id
    private String id;

    private String pteid;

    /** Intel VT-d / AMD-Vi 是否启用（未启用 → 存在 DMA 攻击面）。 */
    private boolean iommuEnabled;

    /** 校验 ACPI DMAR / IVRS 表，检测被篡改/删除以绕过 IOMMU。 */
    private boolean acpiDmacIntegrity;

    /** 检测到内核调试器（NtQuerySystemInformation SystemKernelDebuggerInformation）。 */
    private boolean kernelDebuggerDetected;

    /** PCIe 扫描发现疑似 DMA 设备（FPGA / 数据采集卡 / 未知厂商）。 */
    private boolean pcieSuspicious;

    /** 物理内存读取模式异常（DMA 连续物理页读取）。 */
    private boolean memoryReadAlert;

    /** 用户态调试器 / 硬件断点 / SEH/VEH Hook / 父进程为调试器 任一命中列表。 */
    private String antidebugFindings;

    /** 环境风险分（0-100），进融合评分与分级。 */
    private int score;

    @Enumerated(EnumType.STRING)
    private Level level;

    /** 命中项（逗号分隔），供管理端快速定位。 */
    private String findings;

    @Builder.Default
    private Instant createdAt = Instant.now();
}