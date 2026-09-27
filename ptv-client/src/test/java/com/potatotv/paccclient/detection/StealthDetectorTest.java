package com.potatotv.paccclient.detection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.stealth.StealthSnapshot;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §4 隐身判定测试：DMA / 注入 / 调试 / 沙箱取证如何折算为端侧事件，
 * 以及「未知不判定、稳态不刷事件」两条边界。
 */
class StealthDetectorTest {

    private final StealthDetector detector = new StealthDetector();

    @Test
    void suspiciousDmaWithIommuOffIsHighRisk() {
        Optional<DetectionEvent> event = detector.inspect(snapshot(
                true, List.of("10EE:9038(Xilinx FPGA)"), Boolean.TRUE, List.of(), List.of(), 0,
                false, List.of(), null, null, List.of(), 0, List.of()));

        assertTrue(event.isPresent());
        assertEquals("dma_cheat", event.get().eventType());
        assertEquals("high", event.get().severity());
        assertTrue(event.get().clientRiskScore() >= 90, "DMA + IOMMU 关闭应接近满分：" + event.get().clientRiskScore());
        assertTrue(event.get().detailJson().contains("Xilinx"), "明细应带可疑设备证据");
    }

    @Test
    void unknownThreadsAloneDoNotProduceEvent() {
        Optional<DetectionEvent> event = detector.inspect(snapshot(
                true, List.of(), Boolean.FALSE, List.of(), List.of(), 7,
                false, List.of(), Boolean.FALSE, Boolean.FALSE, List.of(), 1, List.of("cores:2")));

        assertTrue(event.isEmpty(), "线程名未知只是弱信号，不应独立产生事件");
    }

    @Test
    void injectedAgentProducesEvent() {
        Optional<DetectionEvent> event = detector.inspect(snapshot(
                false, List.of(), null, List.of("-javaagent:/tmp/x.jar", "JAVA_TOOL_OPTIONS=-agentlib:foo"),
                List.of("/tmp/.attach_pid1234"), 0,
                false, List.of(), null, null, List.of(), 0, List.of()));

        assertTrue(event.isPresent());
        assertEquals("reflective_dll", event.get().eventType());
        assertTrue(event.get().clientRiskScore() >= 60);
    }

    @Test
    void debuggerChannelIsReportedAsMedium() {
        Optional<DetectionEvent> event = detector.inspect(snapshot(
                false, List.of(), null, List.of(), List.of(), 0,
                true, List.of("-agentlib:jdwp=transport=dt_socket"), null, null, List.of(), 0, List.of()));

        assertTrue(event.isPresent());
        assertEquals("anti_debug", event.get().eventType());
        assertEquals("medium", event.get().severity());
    }

    @Test
    void virtualizationOnlyAppearsInFeaturesNotAsEvent() {
        Optional<DetectionEvent> event = detector.inspect(snapshot(
                false, List.of(), null, List.of(), List.of(), 0,
                false, List.of(), Boolean.TRUE, Boolean.FALSE, List.of("mac:080027(VirtualBox)"), 0, List.of()));

        assertTrue(event.isEmpty(), "单开虚拟机是合法场景，只进特征向量");
    }

    @Test
    void virtualizationWithSandboxIndicatorsIsReported() {
        Optional<DetectionEvent> event = detector.inspect(snapshot(
                false, List.of(), null, List.of(), List.of(), 0,
                false, List.of(), Boolean.TRUE, Boolean.TRUE, List.of("mac:080027(VirtualBox)"),
                4, List.of("cores:2", "memory_gb:2", "disk_gb:40", "host:sandbox-01")));

        assertTrue(event.isPresent());
        assertEquals("sandbox", event.get().eventType());
    }

    @Test
    void unknownSnapshotProducesNothing() {
        assertTrue(detector.inspect((StealthSnapshot) null).isEmpty());
        assertTrue(detector.inspect(snapshot(
                false, List.of(), null, List.of(), List.of(), 0,
                false, List.of(), null, null, List.of(), 0, List.of())).isEmpty());
    }

    private static StealthSnapshot snapshot(boolean pcieEnumerated, List<String> suspiciousPcie,
                                            Boolean iommuDisabled, List<String> agents, List<String> attach,
                                            int unknownThreads, boolean debugger, List<String> debugChannels,
                                            Boolean vm, Boolean hypervisor, List<String> vmEvidence,
                                            int sandbox, List<String> sandboxEvidence) {
        return new StealthSnapshot(pcieEnumerated, suspiciousPcie, iommuDisabled, agents, attach,
                unknownThreads, List.of(), debugger, debugChannels, vm, hypervisor, vmEvidence,
                sandbox, sandboxEvidence, System.currentTimeMillis());
    }
}