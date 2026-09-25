package com.potatotv.paccclient.apm;

import com.sun.management.OperatingSystemMXBean;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 系统维度指标采集：CPU / 内存 / 磁盘 / 网络 / 运行时长 / 负载。
 *
 * <p>数据源优先 {@link OperatingSystemMXBean}（JDK 内省，零依赖）；磁盘与网络速率只有在 Linux 上
 * 才可通过 {@code /proc/diskstats} 与 {@code /proc/net/dev} 的计数器差值推算，Windows/macOS 上无法
 * 从用户态读取等价计数器——此时「不产出该指标」而不是产出 0：服务端把「存在」当作真实数据，
 * 伪造 0 会被当成「磁盘零负载」污染曲线。</p>
 *
 * <p>每个探针独立 try/catch：单点失败只让该维度缺失，绝不影响其他维度与其他链路。首次采样没有
 * 上一次计数器，速率类指标整体跳过。</p>
 */
public final class SystemMetrics {

    /** 一个扇区按 512 字节折算（内核 diskstats 的固定口径）。 */
    private static final long SECTOR_BYTES = 512L;

    private final boolean linux;
    private long prevDiskReadBytes = -1;
    private long prevDiskWriteBytes = -1;
    private long prevDiskNanos;
    private long prevNetRxBytes = -1;
    private long prevNetTxBytes = -1;
    private long prevNetNanos;

    public SystemMetrics() {
        this.linux = System.getProperty("os.name", "").toLowerCase().contains("linux");
    }

    /** 采集一次系统指标并写入快照。任何探针失败都只跳过该指标。 */
    public void collect(ApmSnapshot snapshot) {
        readCpu(snapshot);
        readMemory(snapshot);
        readLoadAvg(snapshot);
        readUptime(snapshot);
        readDisk(snapshot);
        readNet(snapshot);
    }

    /** 本进程 CPU 占用（0-100），供自适应降采样决策使用；未知时返回 0。 */
    public double processCpuPercent() {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean sun) {
                double load = sun.getProcessCpuLoad();
                if (load < 0) return 0.0;
                return clamp(load * 100);
            }
        } catch (RuntimeException | LinkageError e) {
            // com.sun.management 在精简 JRE 上可能不可用：按未知处理
        }
        return 0.0;
    }

    private void readCpu(ApmSnapshot snapshot) {
        try {
            if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean sun) {
                double total = sun.getCpuLoad();
                if (total >= 0) snapshot.with("sys_cpu_total", clamp(total * 100));
                double process = sun.getProcessCpuLoad();
                if (process >= 0) snapshot.with("sys_cpu_process", clamp(process * 100));
            }
        } catch (RuntimeException | LinkageError e) {
            // 忽略：CPU 维度缺失
        }
    }

    private void readMemory(ApmSnapshot snapshot) {
        try {
            if (!(ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean sun)) return;
            long total = sun.getTotalMemorySize();
            long free = sun.getFreeMemorySize();
            if (total > 0) {
                snapshot.with("sys_mem_total", total / 1048576.0);
                if (free >= 0) snapshot.with("sys_mem_used", (total - free) / 1048576.0);
            }
            // 进程占用优先用已提交虚拟内存（含堆外与线程栈），拿不到时退化为堆占用
            long committed = sun.getCommittedVirtualMemorySize();
            snapshot.with("sys_mem_process", committed > 0 ? committed / 1048576.0 : heapUsedMb());
        } catch (RuntimeException | LinkageError e) {
            // 忽略
        }
    }

    private void readLoadAvg(ApmSnapshot snapshot) {
        try {
            double load = ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage();
            if (load >= 0) snapshot.with("sys_load_avg", load);
        } catch (RuntimeException e) {
            // 忽略：部分平台不支持
        }
    }

    private void readUptime(ApmSnapshot snapshot) {
        try {
            Double seconds = null;
            if (linux) {
                seconds = readProcUptimeSeconds();
            }
            if (seconds == null || seconds < 0) {
                // 退化为 JVM 运行时长：数值口径不同但仍是单调计数器，量级可接受
                seconds = ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0;
            }
            snapshot.with("sys_uptime", seconds);
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private void readDisk(ApmSnapshot snapshot) {
        if (!linux) return;
        try {
            long[] counters = readDiskCounters();
            if (counters == null) return;
            long now = System.nanoTime();
            if (prevDiskReadBytes >= 0 && prevDiskNanos > 0) {
                double seconds = (now - prevDiskNanos) / 1_000_000_000.0;
                if (seconds > 0) {
                    snapshot.with("sys_disk_read", rate(counters[0] - prevDiskReadBytes, seconds));
                    snapshot.with("sys_disk_write", rate(counters[1] - prevDiskWriteBytes, seconds));
                }
            }
            // 无论是否产出速率都要推进基线，避免下一次把跨两个周期的增量算成一次
            prevDiskReadBytes = counters[0];
            prevDiskWriteBytes = counters[1];
            prevDiskNanos = now;
        } catch (RuntimeException | java.io.IOException e) {
            // 忽略：/proc 不可读（容器/权限受限）
        }
    }

    private void readNet(ApmSnapshot snapshot) {
        if (!linux) return;
        try {
            long[] counters = readNetCounters();
            if (counters == null) return;
            long now = System.nanoTime();
            if (prevNetRxBytes >= 0 && prevNetNanos > 0) {
                double seconds = (now - prevNetNanos) / 1_000_000_000.0;
                if (seconds > 0) {
                    snapshot.with("sys_net_rx", rate(counters[0] - prevNetRxBytes, seconds));
                    snapshot.with("sys_net_tx", rate(counters[1] - prevNetTxBytes, seconds));
                }
            }
            prevNetRxBytes = counters[0];
            prevNetTxBytes = counters[1];
            prevNetNanos = now;
        } catch (RuntimeException | java.io.IOException e) {
            // 忽略
        }
    }

    /** 解析 /proc/diskstats，返回 {累计读字节, 累计写字节}；不可读时返回 null。 */
    private static long[] readDiskCounters() throws java.io.IOException {
        List<String> lines = Files.readAllLines(Path.of("/proc/diskstats"));
        long read = 0;
        long write = 0;
        boolean any = false;
        for (String line : lines) {
            String[] f = line.trim().split("\\s+");
            // 字段：major minor name reads reads_merged sectors_read ... 0-based 索引 5/9 为扇区数
            if (f.length < 10) continue;
            try {
                read += Long.parseLong(f[5]) * SECTOR_BYTES;
                write += Long.parseLong(f[9]) * SECTOR_BYTES;
                any = true;
            } catch (NumberFormatException ignored) {
                // 单设备解析失败跳过
            }
        }
        return any ? new long[]{read, write} : null;
    }

    /** 解析 /proc/net/dev，返回 {累计接收字节, 累计发送字节}；不可读时返回 null。 */
    private static long[] readNetCounters() throws java.io.IOException {
        List<String> lines = Files.readAllLines(Path.of("/proc/net/dev"));
        long rx = 0;
        long tx = 0;
        boolean any = false;
        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String[] f = line.substring(colon + 1).trim().split("\\s+");
            if (f.length < 9) continue;
            try {
                rx += Long.parseLong(f[0]);
                tx += Long.parseLong(f[8]);
                any = true;
            } catch (NumberFormatException ignored) {
                // 跳过异常行
            }
        }
        return any ? new long[]{rx, tx} : null;
    }

    private static Double readProcUptimeSeconds() {
        try {
            String text = Files.readString(Path.of("/proc/uptime")).trim();
            int space = text.indexOf(' ');
            return Double.parseDouble(space > 0 ? text.substring(0, space) : text);
        } catch (Exception e) {
            return null;
        }
    }

    private static double heapUsedMb() {
        Runtime rt = Runtime.getRuntime();
        return (rt.totalMemory() - rt.freeMemory()) / 1048576.0;
    }

    /** 计数器差值换算为每秒速率；计数器回绕（负数）按 0 处理。 */
    private static double rate(long delta, double seconds) {
        if (delta <= 0 || seconds <= 0) return 0.0;
        return delta / seconds;
    }

    private static double clamp(double x) {
        if (Double.isNaN(x)) return 0.0;
        return Math.max(0.0, Math.min(100.0, x));
    }
}