package com.potatotv.paccclient.detection.stealth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.telemetry.TelemetrySnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §4 探针聚合测试：维度折算与覆盖度语义（未知项不得计入覆盖度）、真机探测不抛异常、
 * TTL 缓存复用同一份取证。
 */
class StealthTelemetryTest {

    @Test
    void mapsKnownFindingsToFeatureDims() {
        StealthSnapshot s = new StealthSnapshot(
                true, List.of("10EE:9038(Xilinx FPGA)"), Boolean.TRUE,
                List.of("-javaagent:/tmp/x.jar"), List.of("/tmp/.attach_pid1"),
                5, List.of("worker-1"),
                true, List.of("-agentlib:jdwp=..."),
                Boolean.TRUE, Boolean.TRUE, List.of("mac:080027(VirtualBox)"),
                3, List.of("cores:2", "memory_gb:2", "disk_gb:40"),
                System.currentTimeMillis());

        TelemetrySnapshot t = StealthTelemetry.toTelemetry(s);

        assertEquals(1.0, t.values().get("feature_pcie_dma_present"));
        assertEquals(1.0, t.values().get("feature_iommu_disabled"));
        assertEquals(5.0, t.values().get("feature_remote_threads"));
        assertEquals(1.0, t.values().get("feature_mod_inject_detected"));
        assertEquals(1.0, t.values().get("feature_debugger_present"));
        assertEquals(1.0, t.values().get("feature_vm_detected"));
        assertEquals(1.0, t.values().get("feature_cpu_hypervisor_bit"));
        assertEquals(3.0, t.values().get("feature_sandbox_indicator_count"));
        for (String key : t.values().keySet()) {
            assertTrue(t.backed().contains(key), "写入的维度都必须计入覆盖度：" + key);
        }
    }

    @Test
    void platformUnknownValuesAreOmittedWhileInProcessFactsStayBacked() {
        StealthSnapshot unknown = new StealthSnapshot(
                false, List.of(), null, List.of(), List.of(), 0, List.of(),
                false, List.of(), null, null, List.of(), 0, List.of(),
                System.currentTimeMillis());

        TelemetrySnapshot t = StealthTelemetry.toTelemetry(unknown);

        // 平台相关项查不到：键完全不写入，避免「0 被当成实测正常」
        assertTrue(t.values().keySet().stream().noneMatch(k -> k.equals("feature_pcie_dma_present")
                || k.equals("feature_iommu_disabled") || k.equals("feature_vm_detected")
                || k.equals("feature_cpu_hypervisor_bit")), "平台未知项不得写入：" + t.values());
        // 进程内取证（线程表、启动参数、沙箱指标）总能得到答案，属真实覆盖
        assertEquals(0.0, t.values().get("feature_remote_threads"));
        assertEquals(0.0, t.values().get("feature_mod_inject_detected"));
        assertEquals(0.0, t.values().get("feature_debugger_present"));
        assertEquals(0.0, t.values().get("feature_sandbox_indicator_count"));
        for (String key : t.values().keySet()) {
            assertTrue(t.backed().contains(key), "写入的维度都必须计入覆盖度：" + key);
        }
    }

    @Test
    void probeRunsOnRealMachineWithoutThrowing() {
        StealthSnapshot s = StealthTelemetry.refresh();

        assertNotNull(s);
        assertTrue(s.probedAtMillis() > 0);
        // 线程数与沙箱项数在真实机器上总可计算（进程内取证，不依赖外部命令）
        assertTrue(s.unknownThreads() >= 0);
        assertTrue(s.sandboxIndicators() >= 0);
    }

    @Test
    void probeReusesCachedSnapshotWithinTtl() {
        StealthSnapshot first = StealthTelemetry.refresh();
        StealthSnapshot second = StealthTelemetry.probe();
        assertSame(first, second, "TTL 内应复用同一份取证，避免每次心跳都跑系统命令");
    }
}