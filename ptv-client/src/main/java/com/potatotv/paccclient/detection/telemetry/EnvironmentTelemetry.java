package com.potatotv.paccclient.detection.telemetry;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * 环境设备遥测（文档 §2.2.1 环境设备 30 维，数据源 WMI/USB 枚举/进程列表）。
 *
 * <p>本类只做纯 JDK 可得的真实内省：CPU/内存/磁盘/进程列表/系统版本/沙箱与虚拟化弱信号。
 * 以下维度依赖平台层（Windows WMI / SetupAPI / 内核驱动枚举），本类恒置 0 且不计入覆盖度，
 * 由 platform 模块与 Java Agent 回填：{@code feature_pcie_dma_present}、{@code feature_iommu_disabled}、
 * {@code feature_hw_perf_counter_anomaly}、{@code feature_unknown_kernel_drivers}、{@code feature_ssdt_hooks}、
 * {@code feature_ghost_client_mem}、{@code feature_remote_threads}、{@code feature_non_image_mem_ratio}、
 * {@code feature_hardware_breakpoint_count}、{@code feature_hid_device_count}、{@code feature_usb_device_count}、
 * {@code feature_screen_refresh_rate}、{@code feature_gpu_vendor_known}、{@code feature_cpu_hypervisor_bit}、
 * {@code feature_thermal_throttle_ratio}。</p>
 *
 * <p>绝不抛出：任一段读取失败仅使对应维度退化为 0。</p>
 */
public final class EnvironmentTelemetry {

    /** 作弊客户端 / DMA 工具 / 调试器进程名关键词。 */
    private static final String[] SUSPICIOUS_NAMES = {
            "wurst", "impact", "sigma", "meteor", "future", "kami", "aristois", "bleachhack",
            "x64dbg", "ollydbg", "ida64", "ida32", "idaq", "cheatengine", "cheat-engine",
            "pchunter", "processhacker", "frida", "shadowdumper", "pcileech", "screamer", "leechcore"};

    /** 常见系统进程 / 运行环境关键词（用于已知进程占比）。 */
    private static final String[] KNOWN_NAMES = {
            "system", "system idle", "svchost", "explorer", "csrss", "wininit", "winlogon", "services",
            "lsass", "dwm", "runtimebroker", "taskhostw", "searchindexer", "conhost", "cmd", "powershell",
            "java", "javaw", "node", "python", "idea", "code", "docker", "mysqld", "redis", "nginx",
            "chrome", "msedge", "firefox", "teams", "wechat", "qq", "explorer.exe", "regsvr32"};

    private EnvironmentTelemetry() {
    }

    /** 采集一次环境快照。 */
    public static TelemetrySnapshot snapshot() {
        Map<String, Double> v = new LinkedHashMap<>();
        Set<String> backed = new HashSet<>();
        readHardware(v, backed);
        readDisk(v, backed);
        readUptimeAndInstallAge(v, backed);
        readProcesses(v, backed);
        readVirtualization(v, backed);
        readSandbox(v, backed);
        readDebugger(v, backed);
        return new TelemetrySnapshot(v, backed);
    }

    private static void readHardware(Map<String, Double> v, Set<String> backed) {
        try {
            v.put("feature_cpu_core_count", (double) Runtime.getRuntime().availableProcessors());
            backed.add("feature_cpu_core_count");
        } catch (RuntimeException e) {
            // 忽略
        }
        try {
            java.lang.management.OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                long total = sun.getTotalMemorySize();
                long free = sun.getFreeMemorySize();
                if (total > 0) {
                    v.put("feature_total_memory_gb", total / 1073741824.0);
                    v.put("feature_free_memory_ratio", clamp01((double) free / total));
                    backed.add("feature_total_memory_gb");
                    backed.add("feature_free_memory_ratio");
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 忽略
        }
    }

    private static void readDisk(Map<String, Double> v, Set<String> backed) {
        try {
            File[] roots = File.listRoots();
            if (roots == null || roots.length == 0) return;
            long total = 0;
            long usable = 0;
            for (File r : roots) {
                try {
                    if (r.getTotalSpace() > 0) {
                        total += r.getTotalSpace();
                        usable += r.getUsableSpace();
                    }
                } catch (RuntimeException ignored) {
                    // 单卷失败跳过
                }
            }
            if (total > 0) {
                v.put("feature_disk_total_gb", total / 1073741824.0);
                v.put("feature_disk_free_ratio", clamp01((double) usable / total));
                backed.add("feature_disk_total_gb");
                backed.add("feature_disk_free_ratio");
            }
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private static void readUptimeAndInstallAge(Map<String, Double> v, Set<String> backed) {
        try {
            // 系统 uptime：Linux 读 /proc/uptime；其余平台以 JVM uptime 作弱代理（沙箱弱信号，文档 §4.3.3）
            double hours = 0;
            boolean ok = false;
            Path proc = Path.of("/proc/uptime");
            if (Files.isReadable(proc)) {
                String line = Files.readString(proc).trim();
                int sp = line.indexOf(' ');
                double sec = Double.parseDouble(sp > 0 ? line.substring(0, sp) : line);
                hours = sec / 3600.0;
                ok = true;
            } else {
                RuntimeMXBean r = ManagementFactory.getRuntimeMXBean();
                hours = r.getUptime() / 3600000.0;
                ok = true;
            }
            if (ok) {
                v.put("feature_system_uptime_hours", hours);
                backed.add("feature_system_uptime_hours");
            }
        } catch (Exception e) {
            // 忽略
        }
        try {
            // 系统安装时长：以文件系统根目录创建时间近似（Windows 根卷创建时间即系统安装时间）
            Path root = Path.of(System.getProperty("user.home", ".")).getRoot();
            if (root != null) {
                FileTime created = (FileTime) Files.getAttribute(root, "creationTime");
                if (created != null) {
                    long days = TimeUnit.MILLISECONDS.toDays(
                            Math.max(0, Instant.now().toEpochMilli() - created.toMillis()));
                    v.put("feature_os_build_age_days", (double) days);
                    backed.add("feature_os_build_age_days");
                }
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    private static void readProcesses(Map<String, Double> v, Set<String> backed) {
        try (Stream<ProcessHandle> ps = ProcessHandle.allProcesses()) {
            int total = 0;
            int suspicious = 0;
            int known = 0;
            for (ProcessHandle p : ps.toList()) {
                total++;
                String name = processName(p);
                if (name.isEmpty()) continue;
                if (matches(name, SUSPICIOUS_NAMES)) suspicious++;
                if (matches(name, KNOWN_NAMES)) known++;
            }
            if (total > 0) {
                v.put("feature_process_count", (double) total);
                v.put("feature_suspicious_process_count", (double) suspicious);
                v.put("feature_signed_process_ratio", clamp01((double) known / total));
                backed.add("feature_process_count");
                backed.add("feature_suspicious_process_count");
                backed.add("feature_signed_process_ratio");
            }
        } catch (RuntimeException | Error e) {
            // SecurityManager / 权限受限：跳过进程组
        }
    }

    private static String processName(ProcessHandle p) {
        try {
            return p.info().command().orElse("").toLowerCase(Locale.ROOT);
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean matches(String name, String[] keys) {
        for (String k : keys) {
            if (name.contains(k)) return true;
        }
        return false;
    }

    private static void readVirtualization(Map<String, Double> v, Set<String> backed) {
        try {
            String probe = (System.getProperty("os.name", "") + " " + System.getProperty("os.version", "")
                    + " " + System.getProperty("os.arch", "")).toLowerCase(Locale.ROOT);
            boolean vm = probe.contains("vmware") || probe.contains("virtualbox") || probe.contains("vbox")
                    || probe.contains("hyper-v") || probe.contains("kvm") || probe.contains("qemu")
                    || probe.contains("xen") || probe.contains("parallels");
            v.put("feature_vm_detected", vm ? 1.0 : 0.0);
            backed.add("feature_vm_detected");
            v.put("feature_system_model_virtual", vm ? 1.0 : 0.0);
            backed.add("feature_system_model_virtual");
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private static void readSandbox(Map<String, Double> v, Set<String> backed) {
        try {
            double cores = Runtime.getRuntime().availableProcessors();
            long totalBytes = 0;
            if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean sun) {
                totalBytes = sun.getTotalMemorySize();
            }
            double diskGb = v.getOrDefault("feature_disk_total_gb", 0.0);
            int indicators = 0;
            if (cores < 4) indicators++;
            if (totalBytes > 0 && totalBytes < 4L * 1073741824L) indicators++;
            if (diskGb > 0 && diskGb < 60) indicators++;
            if (v.getOrDefault("feature_system_uptime_hours", 0.0) < 1.0) indicators++;
            v.put("feature_sandbox_cpu_cores_low", cores < 4 ? 1.0 : 0.0);
            v.put("feature_sandbox_indicator_count", (double) indicators);
            backed.add("feature_sandbox_cpu_cores_low");
            backed.add("feature_sandbox_indicator_count");
        } catch (RuntimeException | LinkageError e) {
            // 忽略
        }
    }

    private static void readDebugger(Map<String, Double> v, Set<String> backed) {
        try {
            RuntimeMXBean r = ManagementFactory.getRuntimeMXBean();
            boolean dbg = false;
            for (String a : r.getInputArguments()) {
                if (a == null) continue;
                String s = a.toLowerCase(Locale.ROOT);
                if (s.contains("jdwp") || s.contains("agentlib:jdwp") || s.contains("-xdebug")) {
                    dbg = true;
                    break;
                }
            }
            v.put("feature_debugger_present", dbg ? 1.0 : 0.0);
            backed.add("feature_debugger_present");
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x)) return 0.0;
        return Math.max(0.0, Math.min(1.0, x));
    }
}