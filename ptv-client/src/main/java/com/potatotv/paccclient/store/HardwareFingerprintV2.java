package com.potatotv.paccclient.store;

import com.potatotv.paccclient.detection.stealth.OsCommand;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * v5.2 §7.1 硬件指纹增强：从注册表 / SMBIOS / DMI 读多维度硬件标识，按稳定性加权成摘要。
 *
 * <p>与既有的 {@link MachineFingerprint} 分工不同：那个是本地加密存储的口令派生（与 C# 侧字节级一致，
 * 换一台机器就解不开配置），本类是<b>反作弊用的设备识别</b>——目标是「同一台机器稳定一致、
 * 不同机器不碰撞、重装系统后仍能认出大半」。</p>
 *
 * <p>维度与权重（§7.1 表）：主板序列号 / 磁盘序列号 / MachineGuid / BIOS 版本 / CPU 型号为高稳定维度
 * （权重 3），MAC / 内存容量 / 系统安装时间为中稳定（权重 2），GPU / 核数为低稳定（权重 1）。
 * <b>加权哈希</b>的实现很直白：按组件权重把 {@code name=value} 行重复相应次数后排序拼接，
 * 再取 SHA-256——高稳定维度被重复计入，某一天换掉显卡不会改变摘要。</p>
 *
 * <p>隐私：只读硬件标识的<b>摘要</b>，上报给 PTV 的也只有哈希（{@link Snapshot#fullHash()} /
 * {@link Snapshot#stableHash()}）；原始序列号不出本机。任一维度读不到就跳过（不算 0、不猜），
 * {@link Snapshot#coverage()} 给出实际读到的高稳定维度数，供验收核对。</p>
 */
public final class HardwareFingerprintV2 {

    /** 单个硬件维度。 */
    public record Component(String name, String value, int weight, boolean stable) {
    }

    /**
     * 一次读取结果。
     *
     * @param components 读到的维度（原始值，仅本机使用）
     * @param fullHash   全维度加权哈希（不含明文）
     * @param stableHash 仅高稳定维度的加权哈希（用于设备突变判定，容错更好）
     * @param coverage   读到的高稳定维度数（满分 5）
     */
    public record Snapshot(List<Component> components, String fullHash, String stableHash, int coverage) {
    }

    /** 高稳定维度总数（覆盖度分母）。 */
    public static final int STABLE_DIMENSIONS = 5;

    private HardwareFingerprintV2() {
    }

    /** 读取一次硬件指纹。 */
    public static Snapshot read() {
        List<Component> components = new ArrayList<>();
        add(components, "cpu", cpuModel(), 3, true);
        add(components, "motherboard", cim("Win32_BaseBoard", "SerialNumber",
                Path.of("/sys/class/dmi/id/board_serial")), 3, true);
        add(components, "disk", diskSerial(), 3, true);
        add(components, "bios", cim("Win32_BIOS", "SMBIOSBIOSVersion",
                Path.of("/sys/class/dmi/id/bios_version")), 3, true);
        add(components, "machine_guid", machineGuid(), 3, true);

        add(components, "mac", firstPhysicalMac(), 2, false);
        add(components, "install_date", installDate(), 2, false);
        add(components, "memory_gb", memoryGb(), 2, false);

        add(components, "gpu", cim("Win32_VideoController", "Name", null), 1, false);
        add(components, "cpu_cores", String.valueOf(Runtime.getRuntime().availableProcessors()), 1, false);

        int stable = 0;
        for (Component c : components) {
            if (c.stable()) stable++;
        }
        return new Snapshot(List.copyOf(components), hash(components, false), hash(components, true), stable);
    }

    /** 加权哈希：按权重重复 {@code name=value} 行，排序后取 SHA-256 十六进制。 */
    static String hash(List<Component> components, boolean stableOnly) {
        List<String> lines = new ArrayList<>();
        for (Component c : components) {
            if (stableOnly && !c.stable()) continue;
            for (int i = 0; i < Math.max(1, c.weight()); i++) {
                lines.add(c.name() + "=" + norm(c.value()));
            }
        }
        lines.sort(Comparator.naturalOrder());
        return sha256Hex(String.join("\n", lines));
    }

    // ------------------------------ 维度读取 ------------------------------

    /** CPU 型号：Windows 走注册表，Linux 读 /proc/cpuinfo 的 model name。 */
    private static String cpuModel() {
        if (OsCommand.isWindows()) {
            return regValue("HKLM\\HARDWARE\\DESCRIPTION\\System\\CentralProcessor\\0", "ProcessorNameString")
                    .orElse(null);
        }
        return firstMatch(Path.of("/proc/cpuinfo"), "model name");
    }

    /** 磁盘序列号：Windows 取物理盘（CIM），Linux 取第一个整盘序列号。 */
    private static String diskSerial() {
        if (OsCommand.isWindows()) return cim("Win32_DiskDrive", "SerialNumber", null);
        return linuxDiskSerial();
    }

    private static String linuxDiskSerial() {
        Path blockDir = Path.of("/sys/block");
        if (!Files.isDirectory(blockDir)) return null;
        try (var dirs = Files.list(blockDir)) {
            for (Path disk : dirs.sorted().toList()) {
                String name = String.valueOf(disk.getFileName());
                if (name.startsWith("loop") || name.startsWith("ram") || name.startsWith("dm-")) continue;
                Optional<String> serial = OsCommand.read(disk.resolve("device/serial"));
                if (serial.isPresent() && !serial.get().isBlank()) return serial.get().trim();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 机器 GUID：Windows 注册表 MachineGuid，Linux /etc/machine-id。 */
    private static String machineGuid() {
        if (OsCommand.isWindows()) {
            return regValue("HKLM\\SOFTWARE\\Microsoft\\Cryptography", "MachineGuid").orElse(null);
        }
        Optional<String> id = OsCommand.read(Path.of("/etc/machine-id"));
        if (id.isEmpty()) id = OsCommand.read(Path.of("/var/lib/dbus/machine-id"));
        return id.map(String::trim).filter(s -> !s.isEmpty()).orElse(null);
    }

    /** 系统安装时间：Windows 注册表 InstallDate（epoch 秒），Linux 用根目录创建时间近似。 */
    private static String installDate() {
        if (OsCommand.isWindows()) {
            return regValue("HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion", "InstallDate").orElse(null);
        }
        try {
            Path root = Path.of(System.getProperty("user.home", ".")).getRoot();
            if (root == null) return null;
            Object created = Files.getAttribute(root, "creationTime");
            return created == null ? null : created.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 首块非虚拟网卡的 MAC（虚拟网卡前缀与隐身探针的判定表一致）。 */
    private static String firstPhysicalMac() {
        try {
            var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                java.net.NetworkInterface ni = ifaces.nextElement();
                if (ni.isLoopback() || ni.isVirtual()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac == null || mac.length < 6) continue;
                String prefix = String.format("%02X%02X%02X", mac[0], mac[1], mac[2]);
                if (VIRTUAL_MAC_PREFIXES.contains(prefix)) continue;
                StringBuilder sb = new StringBuilder(17);
                for (byte b : mac) {
                    if (sb.length() > 0) sb.append(':');
                    sb.append(String.format("%02X", b));
                }
                return sb.toString();
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    private static final java.util.Set<String> VIRTUAL_MAC_PREFIXES =
            java.util.Set.of("000C29", "005056", "080027", "00155D", "525400", "00163E", "02004C");

    private static String memoryGb() {
        try {
            if (java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean sun) {
                long total = sun.getTotalMemorySize();
                return total > 0 ? String.valueOf(total / 1073741824L) : null;
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    // ------------------------------ 命令与文本工具 ------------------------------

    /**
     * CIM/WMI 属性读取：优先 {@code wmic}（Win10 常见），失败退回 PowerShell
     * {@code Get-CimInstance}（Win11 已移除 wmic）。Linux 走 sysfs 文件。
     */
    private static String cim(String className, String property, Path linuxFile) {
        if (!OsCommand.isWindows()) {
            return linuxFile == null ? null : OsCommand.read(linuxFile).map(String::trim)
                    .filter(s -> !s.isEmpty() && !"None".equalsIgnoreCase(s)).orElse(null);
        }
        Optional<String> viaWmic = OsCommand.output("wmic", className, "get", property);
        String value = firstNonHeaderLine(viaWmic.orElse(""), property);
        if (value != null) return value;
        return firstNonHeaderLine(OsCommand.output("powershell", "-NoProfile", "-Command",
                "Get-CimInstance " + className + " | Select-Object -First 1 -ExpandProperty " + property)
                .orElse(""), property);
    }

    /** 注册表值读取（{@code reg query}）。 */
    private static Optional<String> regValue(String key, String name) {
        if (!OsCommand.isWindows()) return Optional.empty();
        Optional<String> out = OsCommand.output("reg", "query", key, "/v", name);
        if (out.isEmpty()) return Optional.empty();
        int i = out.get().lastIndexOf("REG_");
        if (i < 0) return Optional.empty();
        int end = out.get().indexOf('\n', i);
        String line = end < 0 ? out.get().substring(i) : out.get().substring(i, end);
        String[] parts = line.split("\\s{2,}");
        String value = parts.length > 1 ? parts[parts.length - 1].trim() : "";
        return value.isEmpty() ? Optional.empty() : Optional.of(value);
    }

    /** 解析 {@code wmic}/PowerShell 输出：跳过表头与空行，取第一个像是序列号/型号的值。 */
    private static String firstNonHeaderLine(String output, String headerHint) {
        for (String raw : output.split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.equalsIgnoreCase(headerHint)) continue;
            if (line.equalsIgnoreCase("SerialNumber") || line.startsWith("Get-CimInstance")
                    || line.startsWith("Select-Object")) {
                continue;
            }
            return line;
        }
        return null;
    }

    /** 在文件中找 {@code key} 后的第一个非空值（如 /proc/cpuinfo 的 model name）。 */
    private static String firstMatch(Path file, String key) {
        Optional<String> text = OsCommand.read(file);
        if (text.isEmpty()) return null;
        for (String line : text.get().split("\\R")) {
            int i = line.indexOf(key);
            if (i < 0) continue;
            String[] parts = line.split(":", 2);
            String value = parts.length > 1 ? parts[1].trim() : "";
            if (!value.isEmpty()) return value;
        }
        return null;
    }

    private static void add(List<Component> out, String name, String value, int weight, boolean stable) {
        if (value == null || value.isBlank()) return;
        if (value.length() > 128) value = value.substring(0, 128);
        out.add(new Component(name, value, weight, stable));
    }

    /** 归一化：去空白与大小写差异，避免同一台机器因格式细节算出不同摘要。 */
    private static String norm(String s) {
        return s.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 便于本地排查：组件名 → 原始值（仅本机打印，绝不外发）。 */
    public static Map<String, String> rawMap(Snapshot snapshot) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Component c : snapshot.components()) out.put(c.name(), c.value());
        return out;
    }
}