package com.potatotv.paccclient.detection.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.probe.DriverSnapshot;
import com.potatotv.paccclient.probe.MemoryScanResult;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.RegistryHit;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * DF Alpha 1.0.0 P2 检测器测试（文档 §4.3 内存扫描 / §4.9 行为 AI / §3 特征库）。
 */
class SystemScannersP2Test {

    // ========================================================================================
    // MemoryScanner（§4.3）
    // ========================================================================================

    @Test
    void 内存特征码命中触发高严重事件() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.MEMORY);
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe")));
        probe.memoryResults.add(new MemoryScanResult("horion_aimbot", true, List.of(0x140000000L), null));
        probe.memoryResults.add(MemoryScanResult.unsupported("ce_speedhack", "no match"));
        probe.memoryResults.add(MemoryScanResult.unsupported("generic_aimbot_lock", "no match"));
        DetectContext ctx = context(probe);

        DetectionEvent event = new MemoryScanner().detect(ctx).orElseThrow();

        assertEquals("memory_signature", event.eventType());
        assertEquals("high", event.severity());
        assertEquals(1.0, ctx.features().get("ext_memory_supported"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_memory_signature_hits"), 1e-9);
        assertEquals(50.0, ctx.features().get("ext_memory_score"), 1e-9);
    }

    @Test
    void 探针不可用时标记不可用且不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.MEMORY);
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe")));
        DetectContext ctx = context(probe);

        assertTrue(new MemoryScanner().detect(ctx).isEmpty());
        assertEquals(0.0, ctx.features().get("ext_memory_supported"), 1e-9);
    }

    @Test
    void 目标进程未运行时不扫描() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.MEMORY);
        probe.processes = new ProcessSnapshot(List.of(proc("notepad.exe")));
        probe.memoryResults.add(new MemoryScanResult("horion_aimbot", true, List.of(1L), null));
        DetectContext ctx = context(probe);

        assertTrue(new MemoryScanner().detect(ctx).isEmpty());
        assertEquals(0.0, ctx.features().get("ext_memory_supported"), 1e-9);
    }

    @Test
    void 平台不支持内存扫描时降级() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.remove(SystemProbe.Capability.MEMORY);
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe")));

        assertTrue(new MemoryScanner().detect(context(probe)).isEmpty());
    }

    // ========================================================================================
    // BehaviorAIScanner（§4.9）
    // ========================================================================================

    @Test
    void 轨迹过于平滑单指标不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        DetectContext ctx = context(probe);
        ctx.features().put("feature_aim_smoothness", 0.99);

        assertTrue(new BehaviorAIScanner().detect(ctx).isEmpty());
        assertEquals(0.99, ctx.features().get("ext_behavior_trajectory_smoothness"), 1e-9);
        assertEquals(20.0, ctx.features().get("ext_behavior_score"), 1e-9);
    }

    @Test
    void 规律点击叠加高周期性触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        DetectContext ctx = context(probe);
        ctx.features().put("feature_click_interval_cv", 0.1);
        ctx.features().put("feature_click_cps", 10);

        DetectionEvent event = new BehaviorAIScanner().detect(ctx).orElseThrow();

        assertEquals("behavior_anomaly", event.eventType());
        assertEquals("medium", event.severity());
        assertEquals(45.0, ctx.features().get("ext_behavior_score"), 1e-9);
        assertEquals(0.9, ctx.features().get("ext_behavior_periodicity"), 1e-9);
    }

    @Test
    void 多信号叠加达到高严重() {
        FakeSystemProbe probe = new FakeSystemProbe();
        DetectContext ctx = context(probe);
        ctx.features().put("feature_click_interval_cv", 0.1);
        ctx.features().put("feature_click_cps", 10);
        ctx.features().put("feature_aim_smoothness", 0.99);
        ctx.features().put("feature_aim_micro_jitter_entropy", 1.0);

        DetectionEvent event = new BehaviorAIScanner().detect(ctx).orElseThrow();

        assertEquals("high", event.severity());
        assertEquals(95.0, ctx.features().get("ext_behavior_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_behavior_click_entropy"), 1e-9);
    }

    @Test
    void 正常玩家行为不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        DetectContext ctx = context(probe);
        ctx.features().put("feature_click_interval_cv", 0.5);
        ctx.features().put("feature_click_cps", 6);
        ctx.features().put("feature_aim_smoothness", 0.5);
        ctx.features().put("feature_aim_micro_jitter_entropy", 2.5);

        assertTrue(new BehaviorAIScanner().detect(ctx).isEmpty());
        assertEquals(0.0, ctx.features().get("ext_behavior_score"), 1e-9);
    }

    // ========================================================================================
    // SignatureMatcher（§3.2 / §3.3）
    // ========================================================================================

    @Test
    void 内置特征库可装载且覆盖已知签名() {
        List<SignatureMatcher.Signature> signatures = new SignatureMatcher().signatures();

        assertFalse(signatures.isEmpty());
        assertTrue(signatures.stream().anyMatch(s -> "cheat-engine-7.5".equals(s.id())),
                "内置库应包含 Cheat Engine 签名");
    }

    @Test
    void 多指标加权命中签名() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("cheatengine-x86_64.exe")));
        probe.drivers = new DriverSnapshot(List.of(new DriverSnapshot.DriverInfo("dbk64.sys", null, "Running")));
        probe.registryHits = List.of(new RegistryHit("HKCU\\Software\\Cheat Engine", 15));
        DetectContext ctx = context(probe);

        DetectionEvent event = new SignatureMatcher().detect(ctx).orElseThrow();

        assertEquals("signature_hit", event.eventType());
        assertEquals("high", event.severity());
        assertTrue(event.signatureHit().contains("cheat-engine-7.5"));
    }

    @Test
    void 单指标低于签名阈值不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("cheatengine-x86_64.exe")));

        assertTrue(new SignatureMatcher().detect(context(probe)).isEmpty(),
                "仅进程名单指标 30 分 < Cheat Engine 阈值 50");
    }

    @Test
    void 自定义签名按阈值聚合指标() {
        SignatureMatcher matcher = new SignatureMatcher(List.of(
                new SignatureMatcher.Signature("custom", "自定义", "medium", 60,
                        List.of(new SignatureMatcher.Indicator("process_name", "*.exe", 30)))));
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("foo.exe")));

        assertTrue(matcher.detect(context(probe)).isEmpty());

        matcher = new SignatureMatcher(List.of(
                new SignatureMatcher.Signature("custom", "自定义", "medium", 60,
                        List.of(new SignatureMatcher.Indicator("process_name", "*.exe", 30),
                                new SignatureMatcher.Indicator("driver", "foo*.sys", 40)))));
        probe.drivers = new DriverSnapshot(List.of(new DriverSnapshot.DriverInfo("foo.sys", null, "Running")));

        Optional<DetectionEvent> event = matcher.detect(context(probe));
        assertTrue(event.isPresent());
        assertEquals("medium", event.orElseThrow().severity());
    }

    // ========================================================================================
    // 测试夹具
    // ========================================================================================

    private static DetectContext context(SystemProbe probe) {
        return new DetectContext(probe, new FeatureVector());
    }

    private static ProcessSnapshot.ProcessInfo proc(String name) {
        return new ProcessSnapshot.ProcessInfo(4242, name, "C:\\" + name, null);
    }
}