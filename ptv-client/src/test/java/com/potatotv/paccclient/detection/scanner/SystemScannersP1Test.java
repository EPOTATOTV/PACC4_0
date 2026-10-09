package com.potatotv.paccclient.detection.scanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.probe.NetworkSnapshot;
import com.potatotv.paccclient.probe.OsInfo;
import com.potatotv.paccclient.probe.PathHit;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.RegistryHit;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.probe.UsbDevice;
import com.potatotv.paccclient.spi.DetectContext;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * DF Alpha 1.0.0 P1 系统检测器测试（文档 §4.4 / §4.5 / §4.7 / §4.8）。
 *
 * <p>与 P0 相同，用 {@link FakeSystemProbe} 喂入构造好的文件 / 注册表 / USB / 网络快照，
 * 只验检测逻辑、多指标加权阈值与扩展特征写回，不碰真实系统。</p>
 */
class SystemScannersP1Test {

    // ========================================================================================
    // FileScanner（§4.4）
    // ========================================================================================

    @Test
    void 作弊mod文件命中为文件痕迹() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.pathHits = List.of(new PathHit(
                "C:\\Users\\me\\.minecraft\\mods\\wurst.jar", 40));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new FileScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals("cheat_file_trace", event.orElseThrow().eventType());
        assertEquals("medium", event.orElseThrow().severity());
        assertEquals(40.0, ctx.features().get("ext_file_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_file_trace_hits"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_file_mod_hits"), 1e-9);
    }

    @Test
    void 多个mod文件累加达到高严重() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.pathHits = List.of(
                new PathHit("C:\\Users\\me\\.minecraft\\mods\\wurst.jar", 40),
                new PathHit("C:\\Users\\me\\.minecraft\\mods\\meteor-client-1.20.jar", 40));
        DetectContext ctx = context(probe);

        DetectionEvent event = new FileScanner().detect(ctx).orElseThrow();

        assertEquals("high", event.severity());
        assertEquals(80.0, ctx.features().get("ext_file_score"), 1e-9);
        assertEquals(2.0, ctx.features().get("ext_file_mod_hits"), 1e-9);
    }

    @Test
    void 非mod路径不计入mod命中() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.pathHits = List.of(new PathHit(
                "C:\\Users\\me\\AppData\\Roaming\\Cheat Engine\\x.exe", 20));
        DetectContext ctx = context(probe);

        assertTrue(new FileScanner().detect(ctx).isPresent());
        assertEquals(1.0, ctx.features().get("ext_file_trace_hits"), 1e-9);
        assertEquals(0.0, ctx.features().get("ext_file_mod_hits"), 1e-9);
    }

    @Test
    void 无文件命中不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        DetectContext ctx = context(probe);

        assertTrue(new FileScanner().detect(ctx).isEmpty());
        assertEquals(0.0, ctx.features().get("ext_file_score"), 1e-9);
    }

    @Test
    void 平台不支持文件枚举时降级() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.remove(SystemProbe.Capability.FILES);
        probe.pathHits = List.of(new PathHit(
                "C:\\Users\\me\\.minecraft\\mods\\wurst.jar", 40));

        assertTrue(new FileScanner().detect(context(probe)).isEmpty());
    }

    // ========================================================================================
    // RegistryScanner（§4.5）
    // ========================================================================================

    @Test
    void 非Windows不扫描注册表() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.os = new OsInfo("Linux", "6.5", "amd64");
        probe.registryHits = List.of(new RegistryHit(
                "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Image File Execution Options\\Minecraft.Windows.exe", 50));

        assertTrue(new RegistryScanner().detect(context(probe)).isEmpty());
    }

    @Test
    void IFEO劫持键命中计入全局注入() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.registryHits = List.of(new RegistryHit(
                "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Image File Execution Options\\Minecraft.Windows.exe", 50));
        DetectContext ctx = context(probe);

        DetectionEvent event = new RegistryScanner().detect(ctx).orElseThrow();

        assertEquals("cheat_registry_trace", event.eventType());
        assertEquals("high", event.severity());
        assertEquals(1.0, ctx.features().get("ext_registry_ifeo_hits"), 1e-9);
        assertEquals(50.0, ctx.features().get("ext_registry_score"), 1e-9);
    }

    @Test
    void AppInit全局注入键计入全局注入() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.registryHits = List.of(new RegistryHit(
                "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Windows\\AppInit_DLLs", 45));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new RegistryScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals("medium", event.orElseThrow().severity());
        assertEquals(1.0, ctx.features().get("ext_registry_ifeo_hits"), 1e-9);
    }

    @Test
    void 普通作弊键命中为中等严重() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.registryHits = List.of(new RegistryHit("HKCU\\Software\\Cheat Engine", 25));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new RegistryScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals("medium", event.orElseThrow().severity());
        assertEquals(0.0, ctx.features().get("ext_registry_ifeo_hits"), 1e-9);
    }

    @Test
    void 注册表无命中不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();

        assertTrue(new RegistryScanner().detect(context(probe)).isEmpty());
    }

    @Test
    void 平台不支持注册表时降级() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.remove(SystemProbe.Capability.REGISTRY);
        probe.registryHits = List.of(new RegistryHit("HKCU\\Software\\Cheat Engine", 25));

        assertTrue(new RegistryScanner().detect(context(probe)).isEmpty());
    }

    // ========================================================================================
    // InputDeviceScanner（§4.7）
    // ========================================================================================

    @Test
    void MCU设备单命中即触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.usb = List.of(new UsbDevice("VID_2341&PID_0043", "Arduino Uno", "Arduino"));
        DetectContext ctx = context(probe);

        DetectionEvent event = new InputDeviceScanner().detect(ctx).orElseThrow();

        assertEquals("suspicious_input_device", event.eventType());
        assertEquals("medium", event.severity());
        assertEquals(50.0, ctx.features().get("ext_input_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_input_mcu_hits"), 1e-9);
    }

    @Test
    void 游戏外设单独不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.usb = List.of(new UsbDevice("VID_046D&PID_C52B", "Logitech Mouse", "Logitech"));
        DetectContext ctx = context(probe);

        assertTrue(new InputDeviceScanner().detect(ctx).isEmpty());
        assertEquals(0.0, ctx.features().get("ext_input_score"), 1e-9);
        assertEquals(0.0, ctx.features().get("ext_input_mcu_hits"), 1e-9);
    }

    @Test
    void 游戏外设叠加宏软件仍不足阈值() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.usb = List.of(new UsbDevice("VID_046D&PID_C52B", "Logitech Mouse", "Logitech"));
        probe.processes = new ProcessSnapshot(List.of(proc("lghub.exe")));
        DetectContext ctx = context(probe);

        assertTrue(new InputDeviceScanner().detect(ctx).isEmpty());
        assertEquals(15.0, ctx.features().get("ext_input_score"), 1e-9);
        assertEquals(1.0, ctx.features().get("ext_input_macro_software_hits"), 1e-9);
    }

    @Test
    void 平台不支持USB时降级() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.supported.remove(SystemProbe.Capability.USB);
        probe.usb = List.of(new UsbDevice("VID_2341&PID_0043", "Arduino Uno", "Arduino"));

        assertTrue(new InputDeviceScanner().detect(context(probe)).isEmpty());
    }

    // ========================================================================================
    // NetworkScanner（§4.8）
    // ========================================================================================

    @Test
    void C2域名连接命中() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.network = new NetworkSnapshot(List.of(
                conn("horion.xyz", 443, NetworkSnapshot.ConnectionState.ESTABLISHED, "Minecraft.Windows.exe")));
        DetectContext ctx = context(probe);

        DetectionEvent event = new NetworkScanner().detect(ctx).orElseThrow();

        assertEquals("suspicious_network", event.eventType());
        assertEquals(1.0, ctx.features().get("ext_network_c2_hits"), 1e-9);
        assertEquals(50.0, ctx.features().get("ext_network_score"), 1e-9);
    }

    @Test
    void 异常端口累积达到阈值() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.network = new NetworkSnapshot(List.of(
                conn("10.0.0.9", 4444, NetworkSnapshot.ConnectionState.ESTABLISHED, "other.exe"),
                conn("10.0.0.9", 5555, NetworkSnapshot.ConnectionState.ESTABLISHED, "other.exe"),
                conn("10.0.0.9", 31337, NetworkSnapshot.ConnectionState.ESTABLISHED, "other.exe")));
        DetectContext ctx = context(probe);

        Optional<DetectionEvent> event = new NetworkScanner().detect(ctx);

        assertTrue(event.isPresent());
        assertEquals(3.0, ctx.features().get("ext_network_suspicious_port_hits"), 1e-9);
        assertEquals(45.0, ctx.features().get("ext_network_score"), 1e-9);
    }

    @Test
    void 仅游戏进程非标准连接不触发() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.network = new NetworkSnapshot(List.of(
                conn("1.2.3.4", 25566, NetworkSnapshot.ConnectionState.ESTABLISHED, "Minecraft.Windows.exe")));
        DetectContext ctx = context(probe);

        assertTrue(new NetworkScanner().detect(ctx).isEmpty());
        assertEquals(1.0, ctx.features().get("ext_network_mc_nonstandard_hits"), 1e-9);
        assertEquals(10.0, ctx.features().get("ext_network_score"), 1e-9);
    }

    @Test
    void 非ESTABLISHED连接不计入() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.network = new NetworkSnapshot(List.of(
                conn("horion.xyz", 443, NetworkSnapshot.ConnectionState.LISTEN, "other.exe")));
        DetectContext ctx = context(probe);

        assertTrue(new NetworkScanner().detect(ctx).isEmpty());
        assertEquals(0.0, ctx.features().get("ext_network_c2_hits"), 1e-9);
    }

    @Test
    void 自身探针端口被排除() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.network = new NetworkSnapshot(List.of(new NetworkSnapshot.ConnectionInfo(
                "TCP", "127.0.0.1", 17020, "horion.xyz", 443,
                NetworkSnapshot.ConnectionState.ESTABLISHED, 4242, "java.exe")));
        DetectContext ctx = context(probe);

        assertTrue(new NetworkScanner().detect(ctx).isEmpty());
        assertEquals(0.0, ctx.features().get("ext_network_c2_hits"), 1e-9);
    }

    @Test
    void setC2Domains更新清单() {
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.network = new NetworkSnapshot(List.of(
                conn("evil.example", 443, NetworkSnapshot.ConnectionState.ESTABLISHED, "other.exe")));
        DetectContext ctx = context(probe);
        NetworkScanner scanner = new NetworkScanner();
        scanner.setC2Domains(Set.of("evil.example"));

        assertTrue(scanner.detect(ctx).isPresent());
        assertEquals(1.0, ctx.features().get("ext_network_c2_hits"), 1e-9);
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

    private static NetworkSnapshot.ConnectionInfo conn(
            String host, int remotePort, NetworkSnapshot.ConnectionState state, String process) {
        return new NetworkSnapshot.ConnectionInfo(
                "TCP", "10.0.0.1", 50000, host, remotePort, state, 4242, process);
    }
}