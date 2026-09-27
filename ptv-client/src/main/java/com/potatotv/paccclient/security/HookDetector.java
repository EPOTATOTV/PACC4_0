package com.potatotv.paccclient.security;

import java.lang.management.ManagementFactory;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * JVM 级反 hook/反注入检测。
 *
 * <p><b>能做的</b>（纯 Java 可实现，且都是真实可利用的注入手法）：</p>
 * <ul>
 *   <li>环境变量注入：{@code LD_PRELOAD} / {@code DYLD_INSERT_LIBRARIES} 会直接劫持动态链接，
 *       是最廉价也最有效的 hook 手段；{@code JAVA_TOOL_OPTIONS} / {@code _JAVA_OPTIONS}
 *       可在不改变启动命令的情况下注入 agent。</li>
 *   <li>{@code -javaagent} 启动参数：Java 层最正统的字节码改写入口。</li>
 *   <li>类路径/库路径落在临时目录：典型的「影子类」投放点。</li>
 *   <li>影子类前置：用 {@code Class.forName} 取关键类的 {@link ProtectionDomain}，若其
 *       {@link CodeSource} 指向的位置与当前运行位置不一致，说明有一个更早的 classpath 条目
 *       提供了同名类（真实且常见的 Java 攻击）。</li>
 *   <li>类加载量/线程数异常跃升：agent attach 后的通用症状。</li>
 * </ul>
 *
 * <p><b>不能做的</b>（本模块不伪装实现）：字节码完整性校验需要 {@code Instrumentation}
 * （{@code java.lang.instrument} 不在类路径上）；native 层 hook 探测（PLT/GOT、inline hook）
 * 需要原生扩展；JVM 内部函数表改写检测同样需要原生层。</p>
 */
public final class HookDetector {

    /** 判定阈值：加权总分达到该值即认为「疑似被注入/hook」。 */
    public static final int SUSPICIOUS_THRESHOLD = 40;

    /**
     * 关键类：它们的加载来源必须与客户端自身一致。类名以字符串形式参与 {@code Class.forName}，
     * 因此发行混淆时必须保留这些类的名字（见 proguard-rules.pro）。
     */
    private static final String[] CRITICAL_CLASSES = {
            "com.potatotv.paccclient.Json",
            "com.potatotv.paccclient.PaccClient",
            "com.potatotv.paccclient.detection.DetectionEngine"
    };

    /** 可疑路径关键字（临时/共享内存目录常被用作投放点）。 */
    private static final String[] SUSPICIOUS_PATH_HINTS = {
            "/tmp/", "/dev/shm/", "\\temp\\", "\\tmp\\", "appdata\\local\\temp"
    };

    private long prevClassCount = -1;
    private long prevClassMillis;
    private int prevThreadCount = -1;
    private long prevThreadMillis;

    /** 单条发现：kind 用于事件分级，target 是被命中的对象，detail 供排障。 */
    public record Finding(String kind, String target, String detail) {
    }

    /** 一次检测结论：加权得分与全部发现。 */
    public record Assessment(int score, List<Finding> findings) {
    }

    /** 得分是否达到可疑阈值。 */
    public static boolean suspicious(int score) {
        return score >= SUSPICIOUS_THRESHOLD;
    }

    /** 执行一次检测；每个检查独立容错。 */
    public Assessment check() {
        int score = 0;
        List<Finding> findings = new ArrayList<>();
        score += envInjection(findings);
        score += agentArgument(findings);
        score += suspiciousPaths(findings);
        score += shadowClasses(findings);
        score += classGrowth(findings);
        score += threadAnomaly(findings);
        return new Assessment(score, List.copyOf(findings));
    }

    /** 环境变量注入（40/20 分）：廉价且强力的信号，因此权重给得最重。 */
    private int envInjection(List<Finding> findings) {
        int score = 0;
        score += flagEnv(findings, "LD_PRELOAD", 40);
        score += flagEnv(findings, "DYLD_INSERT_LIBRARIES", 40);
        score += flagEnv(findings, "JAVA_TOOL_OPTIONS", 20);
        score += flagEnv(findings, "_JAVA_OPTIONS", 20);
        return score;
    }

    private int flagEnv(List<Finding> findings, String name, int weight) {
        try {
            String value = System.getenv(name);
            if (value != null && !value.isBlank()) {
                findings.add(new Finding("env_injection", name,
                        "环境变量 " + name + "=" + truncate(value)));
                return weight;
            }
        } catch (RuntimeException e) {
            // 忽略
        }
        return 0;
    }

    /** -javaagent 启动参数（30 分）。 */
    private int agentArgument(List<Finding> findings) {
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg != null && arg.startsWith("-javaagent")) {
                    findings.add(new Finding("agent_attach", "-javaagent", "启动参数 " + truncate(arg)));
                    return 30;
                }
            }
        } catch (RuntimeException e) {
            // 忽略
        }
        return 0;
    }

    /** classpath / 本地库路径落在可疑目录（25 分）。 */
    private int suspiciousPaths(List<Finding> findings) {
        try {
            var runtime = ManagementFactory.getRuntimeMXBean();
            String classPath = runtime.getClassPath();
            String libraryPath = runtime.getLibraryPath();
            int score = 0;
            score += flagPath(findings, "classpath", classPath, 25);
            score += flagPath(findings, "library_path", libraryPath, 25);
            return Math.min(score, 25);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private int flagPath(List<Finding> findings, String kind, String path, int weight) {
        if (path == null || path.isBlank()) return 0;
        String lower = path.toLowerCase(Locale.ROOT);
        for (String hint : SUSPICIOUS_PATH_HINTS) {
            if (lower.contains(hint)) {
                findings.add(new Finding("shadow_path", kind, "路径含可疑目录 " + truncate(path)));
                return weight;
            }
        }
        return 0;
    }

    /** 影子类前置（35 分）：关键类的 CodeSource 与运行位置不一致。 */
    private int shadowClasses(List<Finding> findings) {
        try {
            URL ownLocation = ownCodeSource();
            if (ownLocation == null) return 0;
            ClassLoader loader = HookDetector.class.getClassLoader();
            for (String name : CRITICAL_CLASSES) {
                try {
                    Class<?> type = Class.forName(name, false, loader);
                    ProtectionDomain domain = type.getProtectionDomain();
                    CodeSource source = domain == null ? null : domain.getCodeSource();
                    if (source == null || source.getLocation() == null) {
                        findings.add(new Finding("shadow_class", name, "关键类缺少 CodeSource"));
                        return 35;
                    }
                    if (!source.getLocation().equals(ownLocation)) {
                        findings.add(new Finding("shadow_class", name,
                                "加载来源 " + source.getLocation() + " 与运行位置 " + ownLocation + " 不一致"));
                        return 35;
                    }
                } catch (ClassNotFoundException e) {
                    findings.add(new Finding("shadow_class", name, "关键类无法从当前类加载器装载"));
                    return 35;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // 忽略
        }
        return 0;
    }

    /** 类加载量跃升（15 分）。 */
    private int classGrowth(List<Finding> findings) {
        try {
            long loaded = ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
            long now = System.currentTimeMillis();
            int score = 0;
            if (prevClassCount >= 0 && (now - prevClassMillis) <= 60_000 && (loaded - prevClassCount) > 400) {
                findings.add(new Finding("class_redefinition", "class_count",
                        "一分钟内新增类 " + (loaded - prevClassCount)));
                score = 15;
            }
            prevClassCount = loaded;
            prevClassMillis = now;
            return score;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 线程数跃升（15 分）。 */
    private int threadAnomaly(List<Finding> findings) {
        try {
            int count = ManagementFactory.getThreadMXBean().getThreadCount();
            long now = System.currentTimeMillis();
            int score = 0;
            if (prevThreadCount >= 0 && (now - prevThreadMillis) <= 60_000 && (count - prevThreadCount) > 50) {
                findings.add(new Finding("thread_anomaly", "thread_count",
                        "一分钟内新增线程 " + (count - prevThreadCount)));
                score = 15;
            }
            prevThreadCount = count;
            prevThreadMillis = now;
            return score;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 本客户端自身代码来源位置（开发态为 classes 目录，发行态为 jar）。 */
    private static URL ownCodeSource() {
        try {
            ProtectionDomain domain = HookDetector.class.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            return source == null ? null : source.getLocation();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String truncate(String value) {
        return value.length() <= 96 ? value : value.substring(0, 96) + "...";
    }
}