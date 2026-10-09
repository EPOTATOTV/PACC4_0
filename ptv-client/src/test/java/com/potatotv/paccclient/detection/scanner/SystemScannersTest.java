package com.potatotv.paccclient.detection.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.probe.DriverSnapshot;
import com.potatotv.paccclient.probe.ModuleSnapshot;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.ServiceSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * DF Alpha 1.0.0 系统专项检测器测试（文档 §4.1 / §4.2 / §4.6 + §8.3 调度）。
 *
 * <p>这些检测器全部依赖 {@link SystemProbe}，测试用假探针喂入构造好的系统快照，只验检测逻辑与
 * 多指标加权阈值，不碰真实系统。核心断言：单指标不达阈值不触发、多指标加权才命中，
 * 且扩展特征写回供 PRL 规则读取。</p>
 */
class SystemScannersTest {

    // ========================================================================================
    // ProcessScanner
    // ========================================================================================

    @Test
    void 内存修改器进程单命中有分但不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("cheatengine-x86_64.exe", null)));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new ProcessScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals("cheat_process", event.orElseThrow().eventType());
        assertEquals(30.0, ctx.features().get("ext_process_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_process_hits"), 1e-9);
    }

    @Test
    void 调试器进程单命中不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("x64dbg.exe", null)));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new ProcessScanner().detect(ctx);

        assertTrue(event.isEmpty());
        assertEquals(20.0, ctx.features().get("ext_process_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_process_debugger_hits"), 1e-9);
    }

    @Test
    void 进程名与窗口标题多指标加权后命中() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(
                proc("cheatengine-x86_64.exe", "Cheat Engine 7.5")));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new ProcessScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals(45.0, ctx.features().get("ext_process_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_process_window_hits"), 1e-9);
    }

    @Test
    void 窗口标题单项不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("notepad.exe", "Cheat Engine 7.5")));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new ProcessScanner().detect(ctx);

        assertTrue(event.isEmpty());
        assertEquals(15.0, ctx.features().get("ext_process_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_process_window_hits"), 1e-9);
    }

    @Test
    void 基岩作弊客户端按高权重类别计入client_hits() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("Horion.exe", null)));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new ProcessScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals(35.0, ctx.features().get("ext_process_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_process_client_hits"), 1e-9);
    }

    // ========================================================================================
    // ModuleScanner
    // ========================================================================================

    @Test
    void 已知作弊模块命中关键级() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe", null)));
        probe.modules = new ModuleSnapshot("Minecraft.Windows.exe", List.of(
                module("horion.dll", "C:\\games\\horion\\horion.dll"),
                module("ntdll.dll", "C:\\Windows\\System32\\ntdll.dll")));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new ModuleScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals("known_cheat_module", event.orElseThrow().eventType());
        assertEquals("critical", event.orElseThrow().severity());
        assertEquals(1.0, ctx.features().get("ext_module_known_cheat_hits"), 1e-9);
        assertEquals(95.0, ctx.features().get("ext_module_score"), 1e-9);
    }

    @Test
    void 白名单外未知模块达到三个判定可疑() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe", null)));
        probe.modules = new ModuleSnapshot("Minecraft.Windows.exe", List.of(
                module("ntdll.dll", "C:\\Windows\\System32\\ntdll.dll"),
                module("aaa.dll", "C:\\cheats\\aaa.dll"),
                module("bbb.dll", "C:\\cheats\\bbb.dll"),
                module("ccc.dll", "C:\\cheats\\ccc.dll")));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new ModuleScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals("suspicious_module", event.orElseThrow().eventType());
        assertEquals(3.0, ctx.features().get("ext_module_unknown_count"), 1e-9);
        assertEquals(55.0, ctx.features().get("ext_module_score"), 1e-9);
    }

    @Test
    void 目标进程未运行时不扫描模块() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("notepad.exe", null)));
        probe.modules = new ModuleSnapshot("Minecraft.Windows.exe", List.of(
                module("horion.dll", "C:\\games\\horion\\horion.dll")));
        DetectContext ctx = context(probe);

        assertTrue(new ModuleScanner().detect(ctx).isEmpty());
        assertEquals(0.0, ctx.features().get("ext_module_known_cheat_hits"), 1e-9);
    }

    @Test
    void 平台不支持模块枚举时优雅降级() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.remove(SystemProbe.Capability.MODULES);
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe", null)));
        probe.modules = new ModuleSnapshot("Minecraft.Windows.exe", List.of(
                module("horion.dll", "C:\\games\\horion\\horion.dll")));

        assertTrue(new ModuleScanner().detect(context(probe)).isEmpty());
    }

    // ========================================================================================
    // DriverScanner
    // ========================================================================================

    @Test
    void 作弊驱动单项达到阈值即命中() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.drivers = new DriverSnapshot(List.of(new DriverSnapshot.DriverInfo("dbk64.sys", null, "Running")));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new DriverScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals("cheat_driver", event.orElseThrow().eventType());
        assertEquals(40.0, ctx.features().get("ext_driver_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_driver_cheat_hits"), 1e-9);
    }

    @Test
    void 可疑服务单项不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.services = new ServiceSnapshot(
                List.of(new ServiceSnapshot.ServiceInfo("CheatEngine", "Cheat Engine", "Running")));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new DriverScanner().detect(ctx);

        assertTrue(event.isEmpty());
        assertEquals(30.0, ctx.features().get("ext_driver_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_driver_service_hits"), 1e-9);
    }

    // ========================================================================================
    // ScannerRunner
    // ========================================================================================

    @Test
    void 未到周期的检测器不重复执行() {
        FakeSystemProbe probe = new FakeSystemProbe();
        CountingDetector counting = new CountingDetector(1_000_000L, (DetectionEvent) null);
        ScannerRunner runner = new ScannerRunner(List.of(counting));

        runner.tick(context(probe));
        runner.tick(context(probe));

        assertEquals(1, counting.calls, "同一周期内不该重复执行");
    }

    @Test
    void 检测器抛异常不影响其它检测器() {
        FakeSystemProbe probe = new FakeSystemProbe();
        Detector boom = new CountingDetector(1L, new IllegalStateException("boom"));
        CountingDetector good = new CountingDetector(1L,
                new DetectionEvent("cheat_process", "medium", 40));
        ScannerRunner runner = new ScannerRunner(List.of(boom, good));

        Optional<DetectionEvent> event = runner.tick(context(probe));

        assertEquals(1, good.calls, "同周期的正常检测器必须照常执行");
        assertTrue(event.isPresent());
    }

    @Test
    void 返回同周期最高严重度事件() {
        FakeSystemProbe probe = new FakeSystemProbe();
        Detector medium = new CountingDetector(1L, new DetectionEvent("a", "medium", 40));
        Detector critical = new CountingDetector(1L, new DetectionEvent("b", "critical", 90));
        ScannerRunner runner = new ScannerRunner(List.of(medium, critical));

        DetectionEvent top = runner.tick(context(probe)).orElseThrow();

        assertEquals("b", top.eventType());
    }

    @Test
    void 事件驱动型检测器不参与定时调度() {
        FakeSystemProbe probe = new FakeSystemProbe();
        CountingDetector driven = new CountingDetector(0L, (DetectionEvent) null);

        new ScannerRunner(List.of(driven)).tick(context(probe));

        assertEquals(0, driven.calls, "intervalMs=0 表示事件驱动，不由 tick 调度");
    }

    // ========================================================================================
    // 测试夹具
    // ========================================================================================

    private static DetectContext context(SystemProbe probe) {
        return new DetectContext(probe, new FeatureVector());
    }

    private static ProcessSnapshot.ProcessInfo proc(String name, String title) {
        return new ProcessSnapshot.ProcessInfo(4242, name, "C:\\" + name, title);
    }

    private static ModuleSnapshot.ModuleInfo module(String name, String path) {
        return new ModuleSnapshot.ModuleInfo(name, path, 0L, 0L);
    }

    /** 可计调用次数、可注入异常或固定事件的检测器。 */
    private static final class CountingDetector implements Detector {

        private final long interval;
        private final DetectionEvent event;
        private final RuntimeException failure;
        int calls;

        CountingDetector(long interval, DetectionEvent event) {
            this(interval, event, null);
        }

        CountingDetector(long interval, RuntimeException failure) {
            this(interval, null, failure);
        }

        private CountingDetector(long interval, DetectionEvent event, RuntimeException failure) {
            this.interval = interval;
            this.event = event;
            this.failure = failure;
        }

        @Override
        public String id() {
            return "counting";
        }

        @Override
        public long intervalMs() {
            return interval;
        }

        @Override
        public Optional<DetectionEvent> detect(DetectContext ctx) {
            calls++;
            if (failure != null) {
                throw failure;
            }
            return Optional.ofNullable(event);
        }
    }
}