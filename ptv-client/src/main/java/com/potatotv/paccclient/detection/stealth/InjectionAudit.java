package com.potatotv.paccclient.detection.stealth;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 进程注入审计（文档 §4.2，Java 版可观测部分）。
 *
 * <p>纯 JDK 拿不到「线程起始地址是否落在模块范围内」这类原生信息（那是平台层/内核驱动的活），
 * 但注入在 JVM 侧仍留下三类真实痕迹，本类逐一取证：</p>
 * <ol>
 *   <li><b>未知 Agent</b>：{@code -javaagent:}/{@code -agentlib:}/{@code -agentpath:} 启动参数与
 *       {@code JAVA_TOOL_OPTIONS} 等环境变量里的注入项，排除 PACC 自身探针后即为外来注入；</li>
 *   <li><b>动态 attach 痕迹</b>：{@code .attach_pid<pid>} / {@code .java_pid<pid>} 文件存在说明
 *       有进程正在/曾经通过 Attach API 挂到本 JVM（反射式注入的常见入口）；</li>
 *   <li><b>非白名单线程</b>：线程名不属于 JDK / PACC 已知集合的线程数，作为弱信号计入
 *       {@code feature_remote_threads}。</li>
 * </ol>
 *
 * <p>三项都只做取证、不做判定；判定权重在 {@code StealthDetector}。</p>
 */
final class InjectionAudit {

    /** PACC 自家探针标识（出现在 javaagent 路径即视为已知）。 */
    private static final Set<String> PACC_AGENT_MARKERS = Set.of("pacc", "ptv-agent", "ptv_client", "java-agent");

    /** 环境变量注入通道。 */
    private static final String[] AGENT_ENV = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"};

    /** 已知线程（JDK 内部 + PACC 自有）；未命中的线程按未知计数。 */
    private static final Pattern KNOWN_THREAD = Pattern.compile(
            "^(main|Reference Handler|Finalizer|Signal Dispatcher|Common-Cleaner|Notification Thread|"
                    + "Attach Listener|VM Thread|VM Periodic Task Thread|Service Thread|DestroyJavaVM|"
                    + "Sweeper thread|C1 CompilerThread\\d*|C2 CompilerThread\\d*|GC.*|ForkJoinPool.*|"
                    + "Timer.*|Thread-\\d+|ptv-.*|pacc-.*)$");

    /** 报告里最多带出的未知线程名数量（避免把整份线程表塞进事件明细）。 */
    private static final int MAX_NAMES = 5;

    private InjectionAudit() {
    }

    /** 审计结果。 */
    record Audit(List<String> unknownAgents, List<String> attachArtifacts,
                 int unknownThreads, List<String> unknownThreadNames) {

        static Audit empty() {
            return new Audit(List.of(), List.of(), 0, List.of());
        }

        /** 是否存在明确的注入痕迹（未知 Agent 或 attach 文件）。 */
        boolean injected() {
            return !unknownAgents.isEmpty() || !attachArtifacts.isEmpty();
        }
    }

    static Audit audit() {
        List<String> agents = new ArrayList<>();
        collectAgentArgs(agents);
        collectAgentEnv(agents);
        List<String> attach = scanAttachArtifacts();
        List<String> names = new ArrayList<>();
        int unknown = countUnknownThreads(names);
        return new Audit(List.copyOf(agents), List.copyOf(attach), unknown, List.copyOf(names));
    }

    private static void collectAgentArgs(List<String> out) {
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg == null) continue;
                String lower = arg.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("-javaagent:") && !lower.startsWith("-agentlib:")
                        && !lower.startsWith("-agentpath:")) {
                    continue;
                }
                if (!isPaccAgent(lower)) out.add(arg);
            }
        } catch (RuntimeException e) {
            // 取证失败按「无发现」处理
        }
    }

    private static void collectAgentEnv(List<String> out) {
        for (String key : AGENT_ENV) {
            String v;
            try {
                v = System.getenv(key);
            } catch (RuntimeException e) {
                continue;
            }
            if (v == null || v.isBlank()) continue;
            String lower = v.toLowerCase(Locale.ROOT);
            if (lower.contains("-javaagent") || lower.contains("-agentlib") || lower.contains("-agentpath")) {
                if (!isPaccAgent(lower)) out.add(key + "=" + v.trim());
            }
        }
    }

    private static boolean isPaccAgent(String lower) {
        for (String marker : PACC_AGENT_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    /** Attach API 会在临时目录留下 {@code .attach_pid<pid>}；HotSpot 性能数据留 {@code .java_pid<pid>}。 */
    private static List<String> scanAttachArtifacts() {
        List<String> out = new ArrayList<>();
        try {
            String tmp = System.getProperty("java.io.tmpdir");
            if (tmp == null || tmp.isBlank()) return out;
            long pid = ProcessHandle.current().pid();
            Path dir = Path.of(tmp);
            for (String name : new String[]{".attach_pid" + pid, ".java_pid" + pid}) {
                Path f = dir.resolve(name);
                if (Files.exists(f)) out.add(f.toString());
            }
        } catch (RuntimeException e) {
            // 取证失败按「无发现」处理
        }
        return out;
    }

    private static int countUnknownThreads(List<String> names) {
        int unknown = 0;
        try {
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String name = t.getName();
                if (name == null || KNOWN_THREAD.matcher(name).matches()) continue;
                unknown++;
                if (names.size() < MAX_NAMES) names.add(name);
            }
        } catch (RuntimeException | Error e) {
            return 0;
        }
        return unknown;
    }
}