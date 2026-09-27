package com.potatotv.paccclient.detection.stealth;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 反调试检测（文档 §4.3.1，JVM 可观测部分）。
 *
 * <p>PEB / NtGlobalFlag / 硬件断点 / 软件断点这些要读进程内存与调试寄存器，纯 JDK 拿不到（由平台层填充）。
 * JVM 侧真实可见的是「调试通道是否开着」：JDWP 参数、{@code -agentlib:jdwp}、
 * {@code -Xdebug}、以及任何 {@code agentlib} 形式的调试代理；这些既可能来自启动参数，
 * 也可能来自 {@code JAVA_TOOL_OPTIONS} 等环境变量，本类两处都查。</p>
 */
final class DebugAudit {

    private static final String[] ENV_CHANNELS = {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"};
    /** 调试通道特征串。 */
    private static final String[] DEBUG_MARKERS = {"jdwp", "xdebug", "-agentlib:jdwp", "debugger"};

    private DebugAudit() {
    }

    /** 检测到的调试通道（空表示未检出；不代表一定没有调试器，Attach 是动态的）。 */
    record Audit(boolean debuggerPresent, List<String> channels) {
    }

    static Audit audit() {
        List<String> channels = new ArrayList<>();
        try {
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (matches(arg)) channels.add(arg);
            }
        } catch (RuntimeException e) {
            // 忽略：取不到启动参数时按未检出处理
        }
        for (String key : ENV_CHANNELS) {
            String v;
            try {
                v = System.getenv(key);
            } catch (RuntimeException e) {
                continue;
            }
            if (v != null && matches(v)) channels.add(key + "=" + v.trim());
        }
        return new Audit(!channels.isEmpty(), List.copyOf(channels));
    }

    private static boolean matches(String raw) {
        if (raw == null) return false;
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String marker : DEBUG_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }
}