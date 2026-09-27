package com.potatotv.paccclient.detection.stealth;

import java.util.List;

/**
 * 一次隐身探针的取证结果（文档 §4）。
 *
 * <p>三态字段（{@code Boolean} 可为 {@code null}）表示「未知」：平台不支持、命令失败或键不存在时
 * 不做任何推断，既不报警也不计入特征覆盖度。列表字段是原始证据，供事件明细与管理端复核，
 * 采集侧已限制条数（不会把整份线程表/注册表塞进来）。</p>
 *
 * @param pcieEnumerated    PCIe 是否真的枚举成功（false 时 DMA 结论恒为未知）
 * @param suspiciousPcie    命中的可疑 DMA 设备（VID:DID(厂商)）
 * @param iommuDisabled     IOMMU 是否被关闭；{@code null} 表示查不到
 * @param unknownAgents     非 PACC 的 agent 注入项（启动参数或环境变量）
 * @param attachArtifacts   Attach API 留下的痕迹文件
 * @param unknownThreads    非白名单线程数
 * @param unknownThreadNames 其中最多 5 个线程名（证据）
 * @param debuggerPresent   是否检出调试通道
 * @param debugChannels     调试通道证据
 * @param vm                是否虚拟化环境；{@code null} 表示未知
 * @param hypervisor        Hypervisor 位；{@code null} 表示未知
 * @param vmEvidence        虚拟化证据
 * @param sandboxIndicators 沙箱特征命中项数
 * @param sandboxEvidence   沙箱证据
 * @param probedAtMillis    探测时刻
 */
public record StealthSnapshot(boolean pcieEnumerated, List<String> suspiciousPcie,
                              Boolean iommuDisabled,
                              List<String> unknownAgents, List<String> attachArtifacts,
                              int unknownThreads, List<String> unknownThreadNames,
                              boolean debuggerPresent, List<String> debugChannels,
                              Boolean vm, Boolean hypervisor, List<String> vmEvidence,
                              int sandboxIndicators, List<String> sandboxEvidence,
                              long probedAtMillis) {

    /** DMA 设备命中（需要枚举成功）。 */
    public boolean dmaPresent() {
        return pcieEnumerated && !suspiciousPcie.isEmpty();
    }

    /** 是否存在明确注入痕迹（未知 agent 或 attach 文件）。 */
    public boolean injected() {
        return !unknownAgents.isEmpty() || !attachArtifacts.isEmpty();
    }
}