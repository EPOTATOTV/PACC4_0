package com.potatotv.paccclient.detection.telemetry;

import java.lang.management.ClassLoadingMXBean;
import java.lang.management.CompilationMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * JVM 特征遥测（文档 §2.2.1 JVM 特征 20 维，数据源为 Java Agent 探针 / JMX 内省）。
 *
 * <p>全部通过 {@link ManagementFactory} 的纯 JDK MXBean 读取，零第三方依赖；单次读取失败仅使
 * 该维度退化为 0 并标记为「未探测」，绝不抛出。</p>
 */
public final class JvmTelemetry {

    private JvmTelemetry() {
    }

    /** 采集一次 JVM 快照。 */
    public static TelemetrySnapshot snapshot() {
        Map<String, Double> v = new LinkedHashMap<>();
        Set<String> backed = new HashSet<>();
        try {
            readMemory(v, backed);
            readGc(v, backed);
            readThreads(v, backed);
            readClasses(v, backed);
            readCompilation(v, backed);
            readRuntime(v, backed);
            readOs(v, backed);
        } catch (RuntimeException | LinkageError e) {
            // 遥测整体不可用时返回已采集到的部分，保持检测链路可用
            return new TelemetrySnapshot(v, backed);
        }
        return new TelemetrySnapshot(v, backed);
    }

    private static void readMemory(Map<String, Double> v, Set<String> backed) {
        try {
            MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
            MemoryUsage heap = mem.getHeapMemoryUsage();
            long max = heap.getMax();
            if (max > 0) {
                v.put("feature_jvm_heap_used_ratio", clamp01((double) heap.getUsed() / max));
                v.put("feature_jvm_heap_max_mb", max / 1048576.0);
                backed.add("feature_jvm_heap_used_ratio");
                backed.add("feature_jvm_heap_max_mb");
            }
            v.put("feature_jvm_pending_finalization", (double) mem.getObjectPendingFinalizationCount());
            backed.add("feature_jvm_pending_finalization");
        } catch (RuntimeException e) {
            // 忽略：该组维度保持 0
        }
    }

    private static void readGc(Map<String, Double> v, Set<String> backed) {
        try {
            long count = 0;
            long time = 0;
            boolean any = false;
            for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                long c = gc.getCollectionCount();
                long t = gc.getCollectionTime();
                if (c >= 0) {
                    count += c;
                    any = true;
                }
                if (t >= 0) time += t;
            }
            v.put("feature_jvm_gc_count", (double) count);
            v.put("feature_jvm_gc_time_ms", (double) time);
            if (any) backed.add("feature_jvm_gc_count");
            backed.add("feature_jvm_gc_time_ms");
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private static void readThreads(Map<String, Double> v, Set<String> backed) {
        try {
            ThreadMXBean t = ManagementFactory.getThreadMXBean();
            int live = t.getThreadCount();
            int peak = t.getPeakThreadCount();
            int daemon = t.getDaemonThreadCount();
            v.put("feature_jvm_thread_count", (double) live);
            v.put("feature_jvm_peak_thread_count", (double) peak);
            v.put("feature_jvm_daemon_thread_ratio", live <= 0 ? 0.0 : clamp01((double) daemon / live));
            backed.add("feature_jvm_thread_count");
            backed.add("feature_jvm_peak_thread_count");
            backed.add("feature_jvm_daemon_thread_ratio");
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private static void readClasses(Map<String, Double> v, Set<String> backed) {
        try {
            ClassLoadingMXBean c = ManagementFactory.getClassLoadingMXBean();
            v.put("feature_jvm_class_loaded_count", (double) c.getLoadedClassCount());
            v.put("feature_jvm_class_unloaded_count", (double) Math.max(0, c.getUnloadedClassCount()));
            backed.add("feature_jvm_class_loaded_count");
            backed.add("feature_jvm_class_unloaded_count");
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private static void readCompilation(Map<String, Double> v, Set<String> backed) {
        try {
            CompilationMXBean c = ManagementFactory.getCompilationMXBean();
            if (c != null && c.isCompilationTimeMonitoringSupported()) {
                v.put("feature_jvm_jit_compile_time_ms", (double) Math.max(0, c.getTotalCompilationTime()));
                backed.add("feature_jvm_jit_compile_time_ms");
            }
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private static void readRuntime(Map<String, Double> v, Set<String> backed) {
        try {
            RuntimeMXBean r = ManagementFactory.getRuntimeMXBean();
            v.put("feature_jvm_uptime_sec", r.getUptime() / 1000.0);
            backed.add("feature_jvm_uptime_sec");
            var args = r.getInputArguments();
            v.put("feature_jvm_input_args_count", (double) args.size());
            backed.add("feature_jvm_input_args_count");
            boolean agent = false;
            for (String a : args) {
                if (a != null && (a.startsWith("-javaagent") || a.contains("jdwp") || a.contains("agentlib"))) {
                    agent = true;
                    break;
                }
            }
            v.put("feature_jvm_agent_attached", agent ? 1.0 : 0.0);
            backed.add("feature_jvm_agent_attached");
        } catch (RuntimeException e) {
            // 忽略
        }
    }

    private static void readOs(Map<String, Double> v, Set<String> backed) {
        try {
            OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();
            v.put("feature_jvm_available_processors", (double) os.getAvailableProcessors());
            backed.add("feature_jvm_available_processors");
            if (os instanceof com.sun.management.OperatingSystemMXBean sun) {
                double proc = sun.getProcessCpuLoad();
                if (proc >= 0) {
                    v.put("feature_jvm_cpu_load", clamp01(proc));
                    backed.add("feature_jvm_cpu_load");
                }
                double sys = sun.getCpuLoad();
                if (sys >= 0) {
                    v.put("feature_jvm_system_cpu_load", clamp01(sys));
                    backed.add("feature_jvm_system_cpu_load");
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 忽略：com.sun.management 在某些精简 JRE 上不可用
        }
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x)) return 0.0;
        return Math.max(0.0, Math.min(1.0, x));
    }
}