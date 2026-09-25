package com.potatotv.paccclient.detection.stealth;

import com.potatotv.paccclient.detection.telemetry.TelemetrySnapshot;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 隐身探针聚合（文档 §4）：把 PCIe / IOMMU / 注入 / 调试 / 虚拟化 / 沙箱六路取证合成一份
 * {@link StealthSnapshot}，并折算为 178 维特征里的环境与模组维度。
 *
 * <p>扫描含系统命令（{@code reg query} / {@code wmic}），文档要求「启动时执行一次、扫描延迟 &lt;500ms」，
 * 因此结果带 TTL 缓存（默认 10 分钟）；{@link com.potatotv.paccclient.detection.PerfToggles#STEALTH_PROBES}
 * 关闭时返回空快照，全部维度维持中性默认值。</p>
 *
 * <p>覆盖度只记「真的查过」的键：命令不可用、键不存在、非受支持平台一律不写入 backed，
 * 保证 {@code FeatureCollector.coverage()}（验收 A02）不被虚高。</p>
 */
public final class StealthTelemetry {

    /** 结果缓存时长：扫描成本高，10 分钟内复用同一份取证。 */
    private static final long TTL_MILLIS = 10 * 60 * 1000L;

    private static volatile StealthSnapshot cached;

    private StealthTelemetry() {
    }

    /** 取探针结果（TTL 内复用缓存）。 */
    public static StealthSnapshot probe() {
        StealthSnapshot s = cached;
        if (s != null && System.currentTimeMillis() - s.probedAtMillis() < TTL_MILLIS) return s;
        return refresh();
    }

    /** 强制重新探测并刷新缓存。 */
    public static StealthSnapshot refresh() {
        StealthSnapshot fresh;
        try {
            fresh = collect();
        } catch (RuntimeException | LinkageError e) {
            // 探针整体失败：返回「全未知」快照，绝不影响检测主流程
            fresh = empty();
        }
        cached = fresh;
        return fresh;
    }

    /** 折算为特征遥测快照（维度值 + 覆盖度）。 */
    public static TelemetrySnapshot toTelemetry(StealthSnapshot s) {
        Map<String, Double> v = new LinkedHashMap<>();
        Set<String> backed = new HashSet<>();
        if (s == null) return new TelemetrySnapshot(v, backed);

        if (s.pcieEnumerated()) {
            put(v, backed, "feature_pcie_dma_present", s.dmaPresent() ? 1 : 0);
        }
        if (s.iommuDisabled() != null) {
            put(v, backed, "feature_iommu_disabled", Boolean.TRUE.equals(s.iommuDisabled()) ? 1 : 0);
        }
        put(v, backed, "feature_remote_threads", s.unknownThreads());
        put(v, backed, "feature_mod_inject_detected", s.injected() ? 1 : 0);
        put(v, backed, "feature_debugger_present", s.debuggerPresent() ? 1 : 0);
        if (s.vm() != null) {
            put(v, backed, "feature_vm_detected", Boolean.TRUE.equals(s.vm()) ? 1 : 0);
        }
        if (s.hypervisor() != null) {
            put(v, backed, "feature_cpu_hypervisor_bit", Boolean.TRUE.equals(s.hypervisor()) ? 1 : 0);
        }
        put(v, backed, "feature_sandbox_indicator_count", s.sandboxIndicators());
        return new TelemetrySnapshot(v, backed);
    }

    private static void put(Map<String, Double> v, Set<String> backed, String key, double value) {
        v.put(key, value);
        backed.add(key);
    }

    private static StealthSnapshot collect() {
        PcieScan.Scan pcie = PcieScan.scan();
        InjectionAudit.Audit inject = InjectionAudit.audit();
        DebugAudit.Audit debug = DebugAudit.audit();
        VirtualizationAudit.Audit vm = VirtualizationAudit.audit();
        SandboxAudit.Audit sandbox = SandboxAudit.audit();
        Boolean protection = IommuState.dmaProtectionEnabled();
        return new StealthSnapshot(
                pcie.enumerated(), pcie.suspicious(),
                protection == null ? null : !protection,
                inject.unknownAgents(), inject.attachArtifacts(),
                inject.unknownThreads(), inject.unknownThreadNames(),
                debug.debuggerPresent(), debug.channels(),
                vm.vm(), vm.hypervisor(), vm.evidence(),
                sandbox.indicators(), sandbox.evidence(),
                System.currentTimeMillis());
    }

    /** 全未知快照（探针整体失败时的降级值；不写入任何 backed）。 */
    static StealthSnapshot empty() {
        return new StealthSnapshot(false, java.util.List.of(), null,
                java.util.List.of(), java.util.List.of(), 0, java.util.List.of(),
                false, java.util.List.of(),
                null, null, java.util.List.of(),
                0, java.util.List.of(),
                System.currentTimeMillis());
    }
}