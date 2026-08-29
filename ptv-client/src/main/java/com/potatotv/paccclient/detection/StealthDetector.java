package com.potatotv.paccclient.detection;

import java.util.Optional;

/**
 * v4.1 隐身外挂检测器（端侧硬件层/内存层/环境层）：
 * DMA 硬件 / 幽灵客户端 / 反射式注入 / 虚拟化 / 人类行为模拟度 探测。
 * 网络层与行为层由 PTV 五层对抗引擎完成。
 */
public final class StealthDetector {

    /**
     * 采样隐身对抗特征：探测本机环境并构造特征向量。
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
