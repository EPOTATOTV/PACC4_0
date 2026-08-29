package com.potatotv.pacc.service.detection;

import com.potatotv.pacc.domain.CheatType;
import com.potatotv.pacc.domain.FeatureVector;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * v4.1 隐身外挂检测引擎 — 五层对抗架构。
 * <p>硬件层 → 内核层 → 内存层 → 网络层 → 行为层，逐层对抗各类隐身外挂：
 * DMA 硬件作弊、内核级作弊驱动、幽灵客户端、反射式 DLL 注入、数据包级作弊、
 * 延迟作弊、云作弊/Proxy、微自瞄、慢加速/参数微调、反截图/反检测、虚拟化作弊、AI 驱动作弊。</p>
 */
@Slf4j
@Service
public class StealthCheatDetector {

    public record LayerHit(int layer, String layerName, CheatType.Stealth type, String signal, double weight) {}

    public record Verdict(CheatType.Stealth cheatType, boolean detected, double confidence,
                          List<LayerHit> hits, String summary) {}

    /** 五层全量判定：按置信度降序返回所有命中结论。 */
    public List<Verdict> evaluateAll(FeatureVector fv, Map<String, Object> ctx) {
        List<Verdict> out = new ArrayList<>();
        for (CheatType.Stealth t : CheatType.Stealth.values()) {
            evaluate(t, fv, ctx).ifPresent(out::add);
        }
        out.sort((a, b) -> Double.compare(b.confidence(), a.confidence()));
        return out;
    }

    public Optional<Verdict> evaluate(CheatType.Stealth type, FeatureVector fv, Map<String, Object> ctx) {
        List<LayerHit> hits = new ArrayList<>();

        hardwareLayer(type, fv, ctx).ifPresent(hits::add);
        kernelLayer(type, fv, ctx).ifPresent(hits::add);
        memoryLayer(type, fv, ctx).ifPresent(hits::add);
        networkLayer(type, fv, ctx).ifPresent(hits::add);
        behaviorLayer(type, fv, ctx).ifPresent(hits::add);

        if (hits.isEmpty()) {
            return Optional.empty();
        }
        double confidence = Math.min(0.99, hits.stream().mapToDouble(LayerHit::weight).sum() * 0.2);
        boolean detected = hits.size() >= 2 && confidence >= 0.5;
        String summary = type.displayName() + (detected ? " 已判定" : " 可疑")
                + "（命中 " + hits.size() + " 层，置信度 " + String.format("%.1f%%", confidence * 100) + "）";
        return Optional.of(new Verdict(type, detected, confidence, List.copyOf(hits), summary));
    }

    // ---------- 硬件层：PCIe DMA / IOMMU / 硬件性能计数器 ----------
    private Optional<LayerHit> hardwareLayer(CheatType.Stealth t, FeatureVector fv, Map<String, Object> ctx) {
        if (t != CheatType.Stealth.DMA_CHEAT) return Optional.empty();
        double pcie = fv.get("feature_pcie_dma_present");
        double iommu = fv.get("feature_iommu_disabled");
        if (pcie >= 1.0 && iommu >= 1.0) {
            return Optional.of(new LayerHit(1, "硬件层", t, "PCIe DMA 设备存在且 IOMMU 关闭", 0.4));
        }
        return Optional.empty();
    }

    // ---------- 内核层：作弊驱动 / 钩子 / 内核对象 ----------
    private Optional<LayerHit> kernelLayer(CheatType.Stealth t, FeatureVector fv, Map<String, Object> ctx) {
        double unknownDrivers = fv.get("feature_unknown_kernel_drivers");
        double hooks = fv.get("feature_ssdt_hooks");
        if (t == CheatType.Stealth.KERNEL_DRIVER && (unknownDrivers >= 1 || hooks >= 1)) {
            return Optional.of(new LayerHit(2, "内核层", t,
                    "未知内核驱动 " + (int) unknownDrivers + " / SSDT 钩子 " + (int) hooks, 0.35));
        }
        return Optional.empty();
    }

    // ---------- 内存层：幽灵客户端 / 反射式注入 / 异常线程 ----------
    private Optional<LayerHit> memoryLayer(CheatType.Stealth t, FeatureVector fv, Map<String, Object> ctx) {
        double ghost = fv.get("feature_ghost_client_mem");
        double remoteThreads = fv.get("feature_remote_threads");
        double nonImage = fv.get("feature_non_image_mem_ratio");
        switch (t) {
            case GHOST_CLIENT -> {
                if (ghost >= 1.0) {
                    return Optional.of(new LayerHit(3, "内存层", t, "异常内存区域全覆盖扫描命中幽灵客户端", 0.35));
                }
            }
            case REFLECTIVE_DLL -> {
                if (remoteThreads >= 1 || nonImage >= 0.3) {
                    return Optional.of(new LayerHit(3, "内存层", t,
                            "远程线程 " + (int) remoteThreads + " / 非映像内存占比 "
                                    + String.format("%.0f%%", nonImage * 100), 0.35));
                }
            }
            default -> {
                // 其他类型不在此层判定
            }
        }
        return Optional.empty();
    }

    // ---------- 网络层：数据包级 / 延迟 / 云作弊 ----------
    private Optional<LayerHit> networkLayer(CheatType.Stealth t, FeatureVector fv, Map<String, Object> ctx) {
        double packetRatio = fv.get("feature_packet_anomaly_ratio");
        double proxy = fv.get("feature_proxy_detected");
        double latency = fv.get("feature_abnormal_latency");
        switch (t) {
            case PACKET_CHEAT -> {
                if (packetRatio >= 0.5) {
                    return Optional.of(new LayerHit(4, "网络层", t, "数据包异常比例 " + String.format("%.0f%%", packetRatio * 100), 0.3));
                }
            }
            case LAG_CHEAT -> {
                if (latency >= 1.0) {
                    return Optional.of(new LayerHit(4, "网络层", t, "延迟波动异常（延迟作弊特征）", 0.3));
                }
            }
            case CLOUD_CHEAT -> {
                if (proxy >= 1.0) {
                    return Optional.of(new LayerHit(4, "网络层", t, "代理/云作弊节点特征", 0.3));
                }
            }
            default -> {
                // 不参与
            }
        }
        return Optional.empty();
    }

    // ---------- 行为层：微自瞄 / 慢加速 / 虚拟化 / AI 驱动 ----------
    private Optional<LayerHit> behaviorLayer(CheatType.Stealth t, FeatureVector fv, Map<String, Object> ctx) {
        double humanLikeness = fv.get("feature_human_likeness"); // 人类行为模拟度 0-1
        double aimPull = fv.get("feature_aim_target_attraction");
        double slowDrift = fv.get("feature_slow_accel_drift");
        double vm = fv.get("feature_vm_detected");
        double aiPattern = fv.get("feature_ai_pattern_anomaly");
        switch (t) {
            case MICRO_AIMBOT -> {
                if (humanLikeness > 0 && humanLikeness < 0.2 && aimPull >= 0.6) {
                    return Optional.of(new LayerHit(5, "行为层", t,
                            "目标吸引效应异常 + 人类模拟度低 (" + String.format("%.2f", humanLikeness) + ")", 0.35));
                }
            }
            case SLOW_HACK -> {
                if (slowDrift >= 1.0) {
                    return Optional.of(new LayerHit(5, "行为层", t, "慢加速/参数微调漂移特征", 0.3));
                }
            }
            case VIRTUALIZATION -> {
                if (vm >= 1.0) {
                    return Optional.of(new LayerHit(5, "行为层", t, "虚拟化环境特征（规避检测）", 0.3));
                }
            }
            case AI_DRIVEN_CHEAT -> {
                if (aiPattern >= 0.7) {
                    return Optional.of(new LayerHit(5, "行为层", t,
                            "AI 驱动行为模式异常 (score=" + String.format("%.2f", aiPattern) + ")", 0.3));
                }
            }
            default -> {
                // 不参与
            }
        }
        return Optional.empty();
    }

    /** 构造默认空上下文。 */
    public static Map<String, Object> emptyContext() {
        return new LinkedHashMap<>();
    }
}