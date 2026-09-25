package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.stealth.StealthSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 隐身外挂检测器（端侧硬件层/内存层/环境层）：
 * DMA 硬件 / 幽灵客户端 / 反射式注入 / 虚拟化 / 人类行为模拟度 探测。
 * 网络层与行为层由 PTV 五层对抗引擎完成。
 *
 * <p>v5.2 §4：新增 {@link #inspect(StealthSnapshot)}，判定输入改为真实探针取证
 * （PCIe 枚举 / IOMMU 状态 / 注入痕迹 / 调试通道 / 虚拟化 / 沙箱特征，见
 * {@code detection.stealth.StealthTelemetry}）；旧的参数化 {@link #probe} 保留给平台层回填场景。</p>
 */
public final class StealthDetector {

    // ---- v5.2 §4 风险权重 ----
    /** 命中可疑 DMA 设备（Xilinx/Altera 等 FPGA 板卡）。 */
    private static final int W_DMA = 70;
    /** 可疑 DMA 设备叠加 IOMMU 关闭：直读物理内存的完整条件。 */
    private static final int W_DMA_NO_IOMMU = 30;
    /** 注入痕迹基础分（未知 agent / attach 文件）。 */
    private static final int W_INJECT = 60;
    /** 每条额外注入痕迹加分上限。 */
    private static final int W_INJECT_EXTRA = 20;
    /** 检出调试通道（JDWP 等）：玩家机上出现调试通道即上报中风险（文档 §4.3.1）。 */
    private static final int W_DEBUGGER = 50;
    /** 虚拟化 + 多沙箱特征（分析环境嫌疑）。 */
    private static final int W_SANDBOX = 45;
    /** 仅虚拟化：只体现在特征里，不产生事件。 */
    private static final int W_VM_ONLY = 20;
    /** 事件上报下限：低于该分只进特征向量，避免给云端的稳态信号刷事件。 */
    private static final int REPORT_FLOOR = 45;

    /**
     * v5.2 §4 判定入口：基于真实探针快照。
     *
     * <p>判定规则：可疑 PCIe DMA 设备命中即高危；未知 agent/attach 痕迹按注入上报；
     * 调试通道单列中风险；虚拟化只在同时命中多个沙箱特征时才上报（单开虚拟机是合法场景）。</p>
     *
     * @param s 探针快照（{@code null} 或全未知时返回空）
     */
    public Optional<DetectionEvent> inspect(StealthSnapshot s) {
        if (s == null) return Optional.empty();

        int risk = 0;
        String type = null;

        if (s.dmaPresent()) {
            risk += W_DMA;
            type = "dma_cheat";
            if (Boolean.TRUE.equals(s.iommuDisabled())) risk += W_DMA_NO_IOMMU;
        }
        if (s.injected()) {
            int artifacts = s.unknownAgents().size() + s.attachArtifacts().size();
            risk = Math.max(risk, W_INJECT + Math.min(W_INJECT_EXTRA, artifacts * 10));
            if (type == null) type = "reflective_dll";
        }
        if (s.debuggerPresent()) {
            risk = Math.max(risk, W_DEBUGGER);
            if (type == null) type = "anti_debug";
        }
        boolean vmLike = Boolean.TRUE.equals(s.vm()) || Boolean.TRUE.equals(s.hypervisor());
        if (vmLike && s.sandboxIndicators() >= 3) {
            risk = Math.max(risk, W_SANDBOX);
            if (type == null) type = "sandbox";
        } else if (vmLike && risk == 0) {
            risk = W_VM_ONLY;
            if (type == null) type = "virtualization";
        }

        if (risk < REPORT_FLOOR || type == null) return Optional.empty();
        risk = Math.min(100, risk);
        String severity = risk >= 70 ? "high" : "medium";
        return Optional.of(new DetectionEvent(type, severity, risk,
                "javaw.exe", null, null, "win10_x64", detail(s)));
    }

    /** 事件明细：只带取证结论，不含原始注册表 / 线程表全量内容。 */
    private static String detail(StealthSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pcie_dma_present", s.dmaPresent());
        putList(m, "suspicious_pcie", s.suspiciousPcie());
        if (s.iommuDisabled() != null) m.put("iommu_disabled", s.iommuDisabled());
        putList(m, "unknown_agents", s.unknownAgents());
        putList(m, "attach_artifacts", s.attachArtifacts());
        m.put("unknown_threads", s.unknownThreads());
        putList(m, "unknown_thread_names", s.unknownThreadNames());
        if (s.debuggerPresent()) putList(m, "debug_channels", s.debugChannels());
        if (s.vm() != null) m.put("vm", s.vm());
        if (s.hypervisor() != null) m.put("hypervisor", s.hypervisor());
        putList(m, "vm_evidence", s.vmEvidence());
        m.put("sandbox_indicators", s.sandboxIndicators());
        putList(m, "sandbox_evidence", s.sandboxEvidence());
        return Json.encode(m);
    }

    /** 列表按逗号拼接成标量：客户端极简 JSON 编码器只输出一层标量，拼接后仍是可读的合法 JSON。 */
    private static void putList(Map<String, Object> m, String key, List<String> values) {
        if (values == null || values.isEmpty()) return;
        m.put(key, String.join(",", values));
    }

    /**
     * 采样隐身对抗特征：探测本机环境并构造特征向量（平台层回填路径，兼容旧调用）。
     *
     * @param pcieDmaPresent    是否检测到 PCIe DMA 设备（0/1）
     * @param iommuDisabled     IOMMU 是否被关闭（0/1）
     * @param ghostClientMem    幽灵客户端内存区域命中（0/1）
     * @param remoteThreads     异常远程线程数
     * @param vmDetected        虚拟化环境（0/1）
     * @param jitterEntropy     鼠标微抖动熵（人类通常 &gt; 2.5）
     */
    public FeatureVector probe(double pcieDmaPresent, double iommuDisabled, double ghostClientMem,
                               double remoteThreads, double vmDetected, double jitterEntropy) {
        FeatureVector fv = new FeatureVector();
        // ---- 硬件层 ----
        fv.put("feature_pcie_dma_present", pcieDmaPresent);
        fv.put("feature_iommu_disabled", iommuDisabled);
        fv.put("feature_hw_perf_counter_anomaly", 0.0);
        // ---- 内核层 ----
        fv.put("feature_unknown_kernel_drivers", 0);
        fv.put("feature_ssdt_hooks", 0);
        // ---- 内存层 ----
        fv.put("feature_ghost_client_mem", ghostClientMem);
        fv.put("feature_remote_threads", remoteThreads);
        fv.put("feature_non_image_mem_ratio", remoteThreads > 0 ? 0.4 : 0.0);
        // ---- 网络层 ----
        fv.put("feature_packet_anomaly_ratio", 0.0);
        fv.put("feature_proxy_detected", 0.0);
        fv.put("feature_abnormal_latency", 0.0);
        // ---- 行为层 ----
        fv.put("feature_human_likeness", clamp(jitterEntropy / 3.5, 0, 1));
        fv.put("feature_aim_target_attraction", 0.0);
        fv.put("feature_slow_accel_drift", 0.0);
        fv.put("feature_vm_detected", vmDetected);
        fv.put("feature_ai_pattern_anomaly", 0.0);
        return fv;
    }

    /** 端侧预判定：硬件/内存层命中即高风险。 */
    public Optional<DetectionEvent> inspect(FeatureVector fv) {
        boolean dma = fv.get("feature_pcie_dma_present") >= 1 && fv.get("feature_iommu_disabled") >= 1;
        boolean ghost = fv.get("feature_ghost_client_mem") >= 1;
        boolean inject = fv.get("feature_remote_threads") >= 1;
        boolean vm = fv.get("feature_vm_detected") >= 1;
        double likeness = fv.get("feature_human_likeness");

        int risk = (dma ? 70 : 0) + (ghost ? 65 : 0) + (inject ? 60 : 0) + (vm ? 40 : 0);
        if (likeness > 0 && likeness < 0.2) risk += 50;   // 微自瞄（人类模拟度低）
        if (risk <= 0) {
            return Optional.empty();
        }
        String type = firstOf(dma ? "dma_cheat" : null,
                ghost ? "ghost_client" : null,
                inject ? "reflective_dll" : null,
                vm ? "virtualization" : null);
        String severity = risk >= 120 ? "critical" : "high";
        return Optional.of(new DetectionEvent(type, severity, Math.min(100, risk),
                null, null, null, "win10_x64", fv.toDetailJson()));
    }

    private static String firstOf(String... candidates) {
        for (String c : candidates) {
            if (c != null) return c;
        }
        return "stealth";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
