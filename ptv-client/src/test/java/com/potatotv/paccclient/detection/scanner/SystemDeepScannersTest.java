package com.potatotv.paccclient.detection.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.probe.InjectionReport;
import com.potatotv.paccclient.probe.KernelState;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SignatureResult;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 三层检测架构系统进程层增强测试（文档 §4.2 / §4.3 / §4.4 / §4.5）。
 *
 * <p>四个检测器都依赖 {@link SystemProbe} 新增的签名 / 注入 / 内核能力，测试用
 * {@link FakeSystemProbe} 喂结果，只验判定逻辑、多指标加权阈值、{@code ext_sys_score} 取大合并
 * 与扩展特征写回。未签名 EXE 扫描只在临时目录里验证候选筛选逻辑，不碰真实用户目录。</p>
 */
class SystemDeepScannersTest {

    // ========================================================================================
    // DllSignatureScanner（§4.2）
    // ========================================================================================

    @Test
    void 黑名单发布者模块触发高严重() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.SIGNATURE_VERIFY);
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe")));
        probe.moduleSignatures = List.of(
                new SignatureResult("C:\\cheats\\horion.dll", false, "Horion", null),
                new SignatureResult("C:\\Windows\\System32\\ntdll.dll", true, "Microsoft Windows", null));
        DetectContext ctx = context(probe);

        DetectionEvent event = new DllSignatureScanner().detect(ctx).orElseThrow();

        assertEquals("dll_signature_anomaly", event.eventType());
        assertEquals("high", event.severity());
        assertEquals(1.0, ctx.features().get("ext_sys_unsigned_module_count"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_sys_blacklisted_publisher"), 1e-9);
        assertEquals(45.0, ctx.features().get("ext_sys_score"), 1e-9, "黑名单 40 + 未签名 5");
    }

    @Test
    void 目标进程未运行时不验签() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.SIGNATURE_VERIFY);
        probe.processes = new ProcessSnapshot(List.of(proc("notepad.exe")));
        probe.moduleSignatures = List.of(new SignatureResult("x.dll", false, "Horion", null));

        assertTrue(new DllSignatureScanner().detect(context(probe)).isEmpty());
    }

    @Test
    void 探针结果为空时不产事件() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.SIGNATURE_VERIFY);
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe")));
        DetectContext ctx = context(probe);

        assertTrue(new DllSignatureScanner().detect(ctx).isEmpty());
    }

    // ========================================================================================
    // InjectionScanner（§4.3）
    // ========================================================================================

    @Test
    void 远程线程叠加可执行读写区触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.INJECTION);
        probe.injection = new InjectionReport(true, 1, 1, false, 0, null);
        DetectContext ctx = context(probe);

        DetectionEvent event = new InjectionScanner().detect(ctx).orElseThrow();

        assertEquals("injected_client", event.eventType());
        assertEquals("high", event.severity());
        assertEquals(1.0, ctx.features().get("ext_sys_remote_thread_count"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_sys_exec_rw_region_count"), 1e-9);
        assertEquals(45.0, ctx.features().get("ext_sys_score"), 1e-9, "远程线程 25 + 可执行读写区 20");
    }

    @Test
    void 远程线程达阈值单独触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.INJECTION);
        probe.injection = new InjectionReport(true, 3, 0, true, 0, null);
        DetectContext ctx = context(probe);

        DetectionEvent event = new InjectionScanner().detect(ctx).orElseThrow();

        assertEquals("injected_client", event.eventType());
        assertEquals(1.0, ctx.features().get("ext_sys_pending_apc"), 1e-9);
        assertEquals(90.0, ctx.features().get("ext_sys_score"), 1e-9, "远程线程 75 + APC 15");
    }

    @Test
    void 探针不支持注入检测时降级() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.INJECTION);
        probe.injection = InjectionReport.unsupported("no permission");

        assertTrue(new InjectionScanner().detect(context(probe)).isEmpty());
    }

    // ========================================================================================
    // UnsignedExecutableScanner（§4.5）
    // ========================================================================================

    @Test
    void 候选筛选只取exe与dll且受深度限制(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.exe"), "MZ");
        Files.writeString(tmp.resolve("b.dll"), "MZ");
        Files.writeString(tmp.resolve("c.txt"), "text");
        Path d1 = Files.createDirectories(tmp.resolve("d1"));
        Files.writeString(d1.resolve("e.exe"), "MZ");
        Path d3 = Files.createDirectories(d1.resolve("d2").resolve("d3"));
        Files.writeString(d3.resolve("f.dll"), "MZ");
        Path d4 = Files.createDirectories(d3.resolve("d4"));
        Files.writeString(d4.resolve("g.exe"), "MZ");

        List<Path> candidates = UnsignedExecutableScanner.candidateFiles(tmp);
        Set<String> names = candidates.stream()
                .map(p -> p.getFileName().toString())
                .collect(Collectors.toSet());

        assertEquals(Set.of("a.exe", "b.dll", "e.exe", "f.dll"), names,
                "只收 exe/dll，深度 4 层的 g.exe 应在界外");
    }

    @Test
    void 黑名单发布者可执行文件触发(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("a.exe"), "MZ");
        Files.writeString(tmp.resolve("b.dll"), "MZ");
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.SIGNATURE_VERIFY);
        probe.fileSignatures = List.of(
                new SignatureResult("a.exe", false, "Cheat Engine", null),
                new SignatureResult("b.dll", false, null, null));
        DetectContext ctx = context(probe);

        DetectionEvent event = new UnsignedExecutableScanner(List.of(tmp)).detect(ctx).orElseThrow();

        assertEquals("unsigned_executable", event.eventType());
        assertEquals("medium", event.severity());
        assertEquals(2.0, ctx.features().get("ext_sys_unsigned_exe_count"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_sys_blacklisted_exe_count"), 1e-9);
        assertEquals(50.0, ctx.features().get("ext_sys_score"), 1e-9, "黑名单 40 + 未签名 10");
    }

    @Test
    void 无候选文件时不产事件也不写特征(@TempDir Path tmp) {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.SIGNATURE_VERIFY);
        DetectContext ctx = context(probe);

        assertTrue(new UnsignedExecutableScanner(List.of(tmp)).detect(ctx).isEmpty());
        assertEquals(0, ctx.features().extendedCount(), "无候选文件不写任何扩展特征");
    }

    // ========================================================================================
    // KernelCallbackScanner（§4.4）
    // ========================================================================================

    @Test
    void SSDThook触发关键级() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.KERNEL);
        probe.kernel = new KernelState(true, 1, 0, 0, 0, null);
        DetectContext ctx = context(probe);

        DetectionEvent event = new KernelCallbackScanner().detect(ctx).orElseThrow();

        assertEquals("kernel_callback_anomaly", event.eventType());
        assertEquals("critical", event.severity());
        assertEquals(1.0, ctx.features().get("ext_sys_ssdt_hooks"), 1e-9);
        assertEquals(40.0, ctx.features().get("ext_sys_score"), 1e-9);
    }

    @Test
    void 内核能力不支持时不写零值特征只置综合分() {
        FakeSystemProbe probe = new FakeSystemProbe();
        DetectContext ctx = context(probe);

        assertTrue(new KernelCallbackScanner().detect(ctx).isEmpty());
        assertEquals(1, ctx.features().extendedCount(), "只写 ext_sys_score，不写内核各项 0 值");
        assertEquals(0.0, ctx.features().get("ext_sys_score"), 1e-9);
    }

    // ========================================================================================
    // ext_sys_score 跨检测器取大合并 + 能力不支持统一降级
    // ========================================================================================

    @Test
    void 同周期ext_sys_score取较大值不被覆盖() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.add(SystemProbe.Capability.SIGNATURE_VERIFY);
        probe.supported.add(SystemProbe.Capability.KERNEL);
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe")));
        probe.moduleSignatures = List.of(new SignatureResult("horion.dll", false, "Horion", null));
        probe.kernel = new KernelState(true, 1, 0, 0, 0, null);

        DetectContext ctx = context(probe);
        new DllSignatureScanner().detect(ctx);
        new KernelCallbackScanner().detect(ctx);

        assertEquals(45.0, ctx.features().get("ext_sys_score"), 1e-9,
                "后写的内核分 40 不得把 DLL 分 45 覆盖成更小值");
    }

    @Test
    void 四项能力不支持时都不产事件() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(proc("Minecraft.Windows.exe")));
        probe.moduleSignatures = List.of(new SignatureResult("horion.dll", false, "Horion", null));
        probe.injection = new InjectionReport(true, 5, 5, true, 5, null);
        // 内核检测的「能力不支持」经 kernelState().supported=false 表达（默认即为 unsupported）。

        assertTrue(new DllSignatureScanner().detect(context(probe)).isEmpty());
        assertTrue(new InjectionScanner().detect(context(probe)).isEmpty());
        assertTrue(new UnsignedExecutableScanner().detect(context(probe)).isEmpty());
        assertTrue(new KernelCallbackScanner().detect(context(probe)).isEmpty());
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