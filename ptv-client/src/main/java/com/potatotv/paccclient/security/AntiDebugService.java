package com.potatotv.paccclient.security;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 多维反调试评估：按文档「反调试维度表」加权打分，命中即产出 findings。
 *
 * <p><b>诚实说明</b>：下列维度全部是「用户态启发式」——它们能抬高攻击者成本（迫使对方去
 * patch JVM、隐藏线程名、伪造 /proc），但都不是调试器存在的证明。真正的内核级反调试（ptrace
 * 自附加探测、硬件断点寄存器检查、时序校验插桩）需要本模块不具备的原生/驱动层，这里不伪装实现。</p>
 *
 * <p>每个维度都是独立的私有方法并各自 try/catch：单维度探测失败只算作「未命中」，绝不影响其他
 * 维度，也绝不向外抛异常。</p>
 */
public final class AntiDebugService {

    /** 判定阈值：加权总分达到该值即认为「疑似被调试」。 */
    private static final int THRESHOLD = 30;

    /** 已知调试器/注入器进程名（小写，子串匹配）。 */
    private static final String[] DEBUGGER_NAMES = {
            "gdb", "lldb", "strace", "ida", "x64dbg", "ollydbg", "frida"
    };

    private long prevClassCount = -1;
    private long prevClassMillis;
    /** 计时探针的写入点：防止 JIT 把空循环整个消除掉。 */
    private volatile int timingSink;

    /** 一次评估结论：加权得分、是否命中、命中的维度名列表。 */
    public record Assessment(int score, boolean detected, List<String> findings) {
    }

    /** 执行一次评估。任何维度失败都退化为「未命中」。 */
    public Assessment assess() {
        int score = 0;
        List<String> findings = new ArrayList<>();
        if (jdwpAgentArgument()) {
            score += 25;
            findings.add("jdwp_agent");
        }
        if (vmNameOrArgsContainJdwp()) {
            score += 30;
            findings.add("jdwp_vm");
        }
        if (debuggerThreadPresent()) {
            score += 20;
            findings.add("debug_thread");
        }
        if (timingAnomaly()) {
            score += 25;
            findings.add("timing_anomaly");
        }
        if (debugSystemProperties()) {
            score += 15;
            findings.add("debug_props");
        }
        if (tracerPidNonZero()) {
            score += 20;
            findings.add("tracer_pid");
        }
        if (debuggerParentProcess()) {
            score += 15;
            findings.add("debugger_parent");
        }
        if (debuggerProcessScan()) {
            score += 15;
            findings.add("debugger_process");
        }
        if (classRedefinitionJump()) {
            score += 10;
            findings.add("class_jump");
        }
        if (processHandleDebugFlags()) {
            score += 10;
            findings.add("process_args");
        }
        return new Assessment(score, score >= THRESHOLD, List.copyOf(findings));
    }

    /** 维度 1：启动参数里带 JDWP/agent（25 分）。 */
    private boolean jdwpAgentArgument() {
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg == null) continue;
                if (arg.contains("-agentlib:jdwp") || arg.startsWith("-Xdebug") || arg.startsWith("-javaagent")) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // 未命中
        }
        return false;
    }

    /** 维度 2：VM 名或启动参数含 jdwp（30 分）。 */
    private boolean vmNameOrArgsContainJdwp() {
        try {
            RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            String vmName = runtime.getVmName();
            if (vmName != null && vmName.toLowerCase().contains("jdwp")) return true;
            for (String arg : runtime.getInputArguments()) {
                if (arg != null && arg.toLowerCase().contains("jdwp")) return true;
            }
        } catch (RuntimeException e) {
            // 未命中
        }
        return false;
    }

    /** 维度 3：存在名字含 JDWP/debug 的存活线程（20 分）。 */
    private boolean debuggerThreadPresent() {
        try {
            ThreadMXBean threads = ManagementFactory.getThreadMXBean();
            ThreadInfo[] infos = threads.dumpAllThreads(false, false);
            for (ThreadInfo info : infos) {
                if (info == null || info.getThreadName() == null) continue;
                String name = info.getThreadName().toLowerCase();
                if (name.contains("jdwp") || name.contains("debug")) return true;
            }
        } catch (RuntimeException e) {
            // 未命中
        }
        return false;
    }

    /**
     * 维度 4：计时异常（25 分）。固定 2000 次整数运算在正常 JIT 下远低于 1ms；
     * 单步调试会把耗时放大到数量级级别的差异。取多次样本的中位数，避免偶发 GC 误报。
     */
    private boolean timingAnomaly() {
        try {
            long[] samples = new long[5];
            for (int i = 0; i < samples.length; i++) {
                long start = System.nanoTime();
                int acc = 0;
                for (int j = 0; j < 2000; j++) {
                    acc = acc * 31 + j;
                }
                timingSink = acc;
                samples[i] = System.nanoTime() - start;
            }
            Arrays.sort(samples);
            return samples[samples.length / 2] > 200_000_000L;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 维度 5：系统属性里残留调试痕迹（15 分）。 */
    private boolean debugSystemProperties() {
        try {
            String compiler = System.getProperty("java.compiler");
            if (compiler != null && (compiler.toLowerCase().contains("debug") || compiler.contains("jdwp"))) {
                return true;
            }
            Map<String, String> props = ManagementFactory.getRuntimeMXBean().getSystemProperties();
            for (Map.Entry<String, String> entry : props.entrySet()) {
                String key = entry.getKey() == null ? "" : entry.getKey().toLowerCase();
                String value = entry.getValue() == null ? "" : entry.getValue().toLowerCase();
                if ((key.contains("jdwp") || value.contains("jdwp")
                        || value.contains("-xrunjdwp") || value.contains("agentlib:jdwp"))) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // 未命中
        }
        return false;
    }

    /** 维度 6：/proc/self/status 的 TracerPid 非 0（20 分，仅 Linux）。 */
    private boolean tracerPidNonZero() {
        try {
            Path status = Path.of("/proc/self/status");
            if (!Files.isReadable(status)) return false;
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith("TracerPid:")) {
                    String value = line.substring("TracerPid:".length()).trim();
                    return !value.isEmpty() && !"0".equals(value);
                }
            }
        } catch (Exception e) {
            // 未命中（非 Linux 或不支持）
        }
        return false;
    }

    /** 维度 7：父进程是已知调试器（15 分，仅 Linux）。 */
    private boolean debuggerParentProcess() {
        try {
            ProcessHandle parent = ProcessHandle.current().parent().orElse(null);
            if (parent == null) return false;
            Path comm = Path.of("/proc/" + parent.pid() + "/comm");
            if (!Files.isReadable(comm)) return false;
            return matchesDebugger(Files.readString(comm));
        } catch (Exception e) {
            return false;
        }
    }

    /** 维度 8：遍历进程目录（{@code /proc/<pid>/comm}）扫到调试器/注入器进程（15 分，仅 Linux，扫描上限 512 条）。 */
    private boolean debuggerProcessScan() {
        try {
            if (!Files.isDirectory(Path.of("/proc"))) return false;
            try (Stream<Path> entries = Files.list(Path.of("/proc")).limit(512)) {
                for (Path entry : entries.toList()) {
                    Path comm = entry.resolve("comm");
                    try {
                        if (Files.isReadable(comm) && matchesDebugger(Files.readString(comm))) return true;
                    } catch (Exception ignored) {
                        // 单个 pid 目录读取失败跳过（进程可能已退出）
                    }
                }
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 维度 9：类加载量异常跃升（10 分）。运行中的 JVM 一分钟内正常不会新增 400 个类；
     * attach 类工具（VirtualMachine.attach + loadAgent）注入后会立刻拉高该计数。
     */
    private boolean classRedefinitionJump() {
        try {
            long loaded = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
            long now = System.currentTimeMillis();
            boolean detected = prevClassCount >= 0
                    && (now - prevClassMillis) <= 60_000
                    && (loaded - prevClassCount) > 400;
            prevClassCount = loaded;
            prevClassMillis = now;
            return detected;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** 维度 10：进程启动参数里含调试标志（10 分）。 */
    private boolean processHandleDebugFlags() {
        try {
            String[] args = ProcessHandle.current().info().arguments().orElse(null);
            if (args == null) return false;
            for (String arg : args) {
                if (arg == null) continue;
                if (arg.contains("jdwp") || arg.startsWith("-agentlib") || arg.startsWith("-javaagent")) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // 未命中
        }
        return false;
    }

    private static boolean matchesDebugger(String comm) {
        if (comm == null) return false;
        String lower = comm.toLowerCase().trim();
        for (String name : DEBUGGER_NAMES) {
            if (lower.contains(name)) return true;
        }
        return false;
    }
}