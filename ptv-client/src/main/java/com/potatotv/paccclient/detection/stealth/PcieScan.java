package com.potatotv.paccclient.detection.stealth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PCIe 设备扫描（文档 §4.1.1）。
 *
 * <p>Windows 走注册表 {@code HKLM\SYSTEM\CurrentControlSet\Enum\PCI}（{@code reg query} 只列一层子键，
 * 每个设备实例键名形如 {@code VEN_10EE&DEV_9038&SUBSYS_...}&REV_...}）；Linux 走
 * {@code /sys/bus/pci/devices/*}（读 {@code vendor} / {@code device}）。两条路径都只做只读枚举，
 * 不加载驱动、不碰设备。</p>
 *
 * <p>可疑判定：供应商为常见 FPGA / 开发板 / 虚拟设备（DMA 作弊板卡的典型 VID），
 * 或枚举成功但 VID 为零宽设备。无法枚举的平台返回空表——探测不到与「没有」是两回事，
 * 上层据此决定是否计入覆盖度。</p>
 */
final class PcieScan {

    /** 可疑供应商 VID（文档 §4.1.1 表 + FPGA 开发板常见值）。 */
    private static final Map<String, String> SUSPICIOUS_VENDORS = Map.of(
            "10EE", "Xilinx FPGA",
            "1172", "Altera FPGA",
            "1204", "Altera 开发板",
            "1B36", "Red Hat 虚拟设备",
            "1AF4", "VirtIO",
            "1234", "QEMU 虚拟设备");

    private static final Pattern WINDOWS_DEVICE = Pattern.compile("VEN_([0-9A-Fa-f]{4})&DEV_([0-9A-Fa-f]{4})");
    private static final Path LINUX_DEVICES = Path.of("/sys/bus/pci/devices");

    private PcieScan() {
    }

    /** 单个 PCIe 设备。 */
    record Device(String vendorId, String deviceId, String product) {
    }

    /** 枚举结果：设备表 + 是否真的枚举成功（false 表示平台不支持/命令失败）。 */
    record Scan(List<Device> devices, boolean enumerated, List<String> suspicious) {
        static Scan unsupported() {
            return new Scan(List.of(), false, List.of());
        }
    }

    static Scan scan() {
        if (OsCommand.isWindows()) return scanWindows();
        if (OsCommand.isLinux()) return scanLinux();
        return Scan.unsupported();
    }

    private static Scan scanWindows() {
        Optional<String> out = OsCommand.output("reg", "query",
                "HKLM\\SYSTEM\\CurrentControlSet\\Enum\\PCI");
        if (out.isEmpty()) return Scan.unsupported();
        Map<String, Device> unique = new LinkedHashMap<>();
        for (String line : out.get().split("\\R")) {
            Matcher m = WINDOWS_DEVICE.matcher(line);
            if (!m.find()) continue;
            String vid = m.group(1).toUpperCase(Locale.ROOT);
            String did = m.group(2).toUpperCase(Locale.ROOT);
            unique.putIfAbsent(vid + ":" + did, new Device(vid, did, ""));
        }
        return result(unique.values());
    }

    private static Scan scanLinux() {
        if (!Files.isDirectory(LINUX_DEVICES)) return Scan.unsupported();
        List<Device> devices = new ArrayList<>();
        try (var dirs = Files.list(LINUX_DEVICES)) {
            for (Path dir : dirs.toList()) {
                String vid = hex(OsCommand.read(dir.resolve("vendor")).orElse(""));
                String did = hex(OsCommand.read(dir.resolve("device")).orElse(""));
                if (vid.isEmpty()) continue;
                devices.add(new Device(vid, did, ""));
            }
        } catch (Exception e) {
            return Scan.unsupported();
        }
        return result(devices);
    }

    private static Scan result(java.util.Collection<Device> devices) {
        List<String> suspicious = new ArrayList<>();
        for (Device d : devices) {
            String vendor = SUSPICIOUS_VENDORS.get(d.vendorId());
            if (vendor != null) suspicious.add(d.vendorId() + ":" + d.deviceId() + "(" + vendor + ")");
        }
        return new Scan(List.copyOf(devices), true, List.copyOf(suspicious));
    }

    /** {@code 0x10ee\n} → {@code 10EE}。 */
    private static String hex(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return "";
        if (s.startsWith("0x") || s.startsWith("0X")) s = s.substring(2);
        return s.length() > 4 ? s.substring(s.length() - 4) : s.toUpperCase(Locale.ROOT);
    }
}