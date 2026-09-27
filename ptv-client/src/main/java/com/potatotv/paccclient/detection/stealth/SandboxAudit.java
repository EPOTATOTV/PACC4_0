package com.potatotv.paccclient.detection.stealth;

import java.awt.GraphicsEnvironment;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 反沙箱检测（文档 §4.3.3）。
 *
 * <p>逐项核对沙箱的典型特征并计数：低核数（&lt;4）、低内存（&lt;4GB）、低磁盘（&lt;60GB）、
 * 系统刚启动（&lt;1 小时）、无图形环境（headless）、主机名命中沙箱命名（sandbox / cuckoo /
 * malware / analysis / docker 等）。这些指标单独看都可能出现在真实机器上，
 * 因此只输出命中项与证据，权重交给 {@code StealthDetector}（多指标同时命中才算可疑）。</p>
 *
 * <p>不做浏览器历史、最近文档之类的取证：与反作弊无关，且触碰玩家隐私，文档 §10.3 也明确限制范围。</p>
 */
final class SandboxAudit {

    /** 沙箱/分析环境常见主机名关键词。 */
    private static final Pattern SANDBOX_HOST = Pattern.compile(
            ".*(sandbox|malware|virus|analys|cuckoo|sample|wdagent|docker|container|vmware|vbox).*",
            Pattern.CASE_INSENSITIVE);

    /** 判定阈值（文档 §4.3.3）。 */
    private static final int MIN_CORES = 4;
    private static final long MIN_MEMORY_BYTES = 4L * 1024 * 1024 * 1024;
    private static final long MIN_DISK_GB = 60;
    private static final double MIN_UPTIME_HOURS = 1.0;

    private SandboxAudit() {
    }

    /** 审计结果：命中项计数 + 证据（如 {@code "cores:2"}）。 */
    record Audit(int indicators, List<String> evidence) {
    }

    static Audit audit() {
        List<String> evidence = new ArrayList<>();
        int cores = Runtime.getRuntime().availableProcessors();
        if (cores < MIN_CORES) evidence.add("cores:" + cores);

        long totalMemory = totalMemoryBytes();
        if (totalMemory > 0 && totalMemory < MIN_MEMORY_BYTES) {
            evidence.add("memory_gb:" + (totalMemory / 1073741824L));
        }

        long diskGb = totalDiskGb();
        if (diskGb > 0 && diskGb < MIN_DISK_GB) evidence.add("disk_gb:" + diskGb);

        double uptimeHours = uptimeHours();
        if (uptimeHours >= 0 && uptimeHours < MIN_UPTIME_HOURS) {
            evidence.add(String.format(Locale.ROOT, "uptime_h:%.2f", uptimeHours));
        }

        if (isHeadless()) evidence.add("headless");
        String host = hostname();
        if (!host.isEmpty() && SANDBOX_HOST.matcher(host).matches()) evidence.add("host:" + host);

        return new Audit(evidence.size(), List.copyOf(evidence));
    }

    private static long totalMemoryBytes() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean()
                    instanceof com.sun.management.OperatingSystemMXBean sun) {
                return sun.getTotalMemorySize();
            }
        } catch (RuntimeException | LinkageError e) {
            // 不可得
        }
        return 0;
    }

    private static long totalDiskGb() {
        try {
            File[] roots = File.listRoots();
            if (roots == null) return 0;
            long total = 0;
            for (File r : roots) {
                try {
                    total += Math.max(0, r.getTotalSpace());
                } catch (RuntimeException ignored) {
                    // 单卷失败跳过
                }
            }
            return total / 1073741824L;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 系统运行时长（小时）：Linux 读 /proc/uptime，其余平台退化为 JVM 运行时长（弱证据）。 */
    private static double uptimeHours() {
        try {
            String proc = OsCommand.read(java.nio.file.Path.of("/proc/uptime")).orElse("");
            if (!proc.isBlank()) {
                int sp = proc.trim().indexOf(' ');
                String sec = sp > 0 ? proc.trim().substring(0, sp) : proc.trim();
                return Double.parseDouble(sec) / 3600.0;
            }
            RuntimeMXBean r = ManagementFactory.getRuntimeMXBean();
            return r.getUptime() / 3600000.0;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static boolean isHeadless() {
        try {
            return GraphicsEnvironment.isHeadless();
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    private static String hostname() {
        try {
            String env = System.getenv("COMPUTERNAME");
            if (env != null && !env.isBlank()) return env.trim();
            String host = System.getenv("HOSTNAME");
            if (host != null && !host.isBlank()) return host.trim();
            return java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "";
        }
    }
}