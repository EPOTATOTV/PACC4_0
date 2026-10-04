package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.probe.UsbDevice;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 输入设备检测（文档 §4.7）：识别宏 / 硬件作弊设备。
 *
 * <p>MCU / HID 模拟设备（Arduino / Teensy / RP2040 等开源开发板）在游戏场景下极不正常，
 * 命中即判定（+50）；游戏外设（罗技 / 雷蛇 / 海盗船等）本身合规，只有与宏软件同时存在才
 * 计 +15 —— 单靠外设不触发，避免竞技玩家误报（文档 §9 风险表）。</p>
 *
 * <p>产出扩展特征（{@code ext_input_}*），供 {@code suspicious_input_device} PRL 规则读取。</p>
 */
public final class InputDeviceScanner implements Detector {

    private static final String ID = "input_device_scanner";
    private static final long INTERVAL_MS = 15_000L;
    private static final int MCU_WEIGHT = 50;
    private static final int PERIPHERAL_WEIGHT = 15;
    private static final int THRESHOLD = 40;

    /** MCU / HID 模拟设备厂商 ID（高风险）。 */
    private static final Set<String> MCU_DEVICES = Set.of(
            "VID_2341", "VID_16C0", "VID_2E8A", "VID_04D8", "VID_1B4F",
            "VID_0483", "VID_1915", "VID_10C4", "VID_1A86", "VID_0403",
            "VID_239A", "VID_2A03", "VID_1D50", "VID_16D0", "VID_03EB");

    /** 游戏外设品牌厂商 ID（需结合宏软件判断）。 */
    private static final Set<String> GAMING_PERIPHERALS = Set.of(
            "VID_046D", "VID_1532", "VID_1038", "VID_1B1C", "VID_0951",
            "VID_2516", "VID_0C45", "VID_18F8", "VID_200A", "VID_04B4", "VID_258A");

    /** 宏软件进程名（小写）。 */
    private static final Set<String> MACRO_SOFTWARE = Set.of(
            "lghub.exe", "lcore.exe",
            "razersynapse.exe", "rzsynapse.exe",
            "steelseriesengine.exe", "sse.exe",
            "corsaircue.exe", "icue.exe",
            "hyperxngenuity.exe", "roccatswarm.exe",
            "coolermasterportal.exe", "logigaming.exe",
            "synapse.exe", "aura.exe", "armourycrate.exe");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        if (!ctx.systemProbe().isSupported(SystemProbe.Capability.USB)) {
            return Optional.empty();
        }
        List<UsbDevice> devices = ctx.systemProbe().enumerateUsb();
        List<String> macroSoftware = macroSoftwareProcesses(ctx);

        List<String> suspicious = new ArrayList<>();
        int score = 0;
        int mcuHits = 0;

        for (UsbDevice device : devices) {
            String id = device.vidPid() == null ? "" : device.vidPid().toUpperCase(Locale.ROOT);
            if (id.isEmpty()) {
                continue;
            }
            if (startsWithAny(id, MCU_DEVICES)) {
                suspicious.add("mcu:" + id + " " + device.description());
                score += MCU_WEIGHT;
                mcuHits++;
            } else if (!macroSoftware.isEmpty() && startsWithAny(id, GAMING_PERIPHERALS)) {
                suspicious.add("gaming_peripheral_with_macro_sw:" + id);
                score += PERIPHERAL_WEIGHT;
            }
        }

        ctx.putExtended("ext_input_score", Math.min(100, score));
        ctx.putExtended("ext_input_mcu_hits", mcuHits);
        ctx.putExtended("ext_input_macro_software_hits", macroSoftware.size());

        if (score < THRESHOLD) {
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("devices", suspicious);
        detail.put("macro_software", macroSoftware);
        detail.put("score", Math.min(100, score));
        return Optional.of(new DetectionEvent(
                "suspicious_input_device",
                score >= 60 ? "high" : "medium",
                Math.min(100, score),
                String.join(",", suspicious), null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    private static List<String> macroSoftwareProcesses(DetectContext ctx) {
        List<String> out = new ArrayList<>();
        ProcessSnapshot snapshot = ctx.systemProbe().snapshotProcesses();
        for (ProcessSnapshot.ProcessInfo process : snapshot.processes()) {
            String name = process.name() == null ? "" : process.name().toLowerCase(Locale.ROOT);
            if (MACRO_SOFTWARE.contains(name) && !out.contains(name)) {
                out.add(name);
            }
        }
        return out;
    }

    private static boolean startsWithAny(String id, Set<String> prefixes) {
        for (String prefix : prefixes) {
            if (id.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}