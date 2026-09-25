package com.potatotv.paccclient.security;

import com.potatotv.paccclient.apm.ClientHealthMetrics;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 进程自保护（尽力而为，全部容错、绝不阻断主流程）。
 *
 * <p><b>能做的（纯 Java 可达）</b>：</p>
 * <ul>
 *   <li>关闭 core dump 过滤：向 {@code /proc/self/coredump_filter} 写 0，避免崩溃时把堆内存
 *       （含 WSS 密钥、会话密钥）落盘被取证（仅 Linux，且仅在可写时）。</li>
 *   <li>退出守卫：注册 JVM 关闭钩子，若退出时没有「正常关闭」标记，计一次崩溃指标。</li>
 *   <li>线程加固：记录非常驻线程名便于排障，并安装默认未捕获异常处理器把崩溃计入指标。</li>
 *   <li>确认可读取自身进程身份（{@link ProcessHandle#info()}），作为最基本的自省可用性检查。</li>
 * </ul>
 *
 * <p><b>不能做的（本模块不伪装实现）</b>：</p>
 * <ul>
 *   <li>POSIX 信号加固（拦截 SIGTERM/SIGSTOP、屏蔽 ptrace 附加）：需要 {@code sun.misc.Signal}
 *       或原生 {@code sigaction}，前者不在公开 JDK、后者需要原生层。</li>
 *   <li>{@code PR_SET_DUMPABLE=0} / {@code ptrace_scope} 改写：前者需 {@code prctl} 系统调用，
 *       后者对非特权用户不可写，这里不做无意义的尝试。</li>
 *   <li>Windows {@code SetProcessMitigationPolicy}：纯 Java 不可达。</li>
 * </ul>
 *
 * <p>因此 OS 级的终止/挂起阻断与深度自保护必须由配套原生层/驱动提供，本模块只覆盖用户态可达子集。</p>
 */
public final class ProcessProtector {

    /** 自保护执行结果。 */
    public record ProtectionReport(boolean coredumpDisabled, boolean crashHandlerInstalled,
                                   int nonDaemonThreads, List<String> notes) {
    }

    /** 正常关闭标记：由客户端自己的关闭钩子置位。 */
    private volatile boolean cleanExit;

    /** 是否已经装过退出守卫，避免重复注册多个钩子。 */
    private volatile boolean exitGuardInstalled;

    /** 执行全部可达的自保护动作并返回结果。 */
    public ProtectionReport apply() {
        List<String> notes = new ArrayList<>();
        boolean identityReadable = identifySelf(notes);
        if (!identityReadable) {
            notes.add("无法读取自身进程身份（受权限或沙箱限制）");
        }
        boolean coredumpDisabled = disableCoreDump(notes);
        boolean crashHandlerInstalled = hardenThreads(notes);
        installSelfExitGuard();
        int nonDaemon = countNonDaemonThreads();
        return new ProtectionReport(coredumpDisabled, crashHandlerInstalled, nonDaemon, List.copyOf(notes));
    }

    /** 标记为正常关闭：退出守卫据此不再计崩溃。 */
    public void markCleanExit() {
        cleanExit = true;
    }

    /**
     * 退出守卫。JVM 关闭钩子无法区分「正常退出」与「被信号终止」（SIGKILL 甚至不会触发钩子），
     * 因此这里等到短暂延时后再看正常关闭标记是否已被客户端钩子置位，未置位即计一次崩溃。
     */
    public void installSelfExitGuard() {
        if (exitGuardInstalled) return;
        exitGuardInstalled = true;
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    // 给客户端自身的关闭钩子留出置位时间：钩子并发执行、顺序不保证
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (!cleanExit) {
                    ClientHealthMetrics.SINK.onCrash();
                    System.err.println("[PTV-Security] 检测到非正常退出（未见正常关闭标记），已计入崩溃指标");
                }
            }, "ptv-exit-guard"));
        } catch (IllegalStateException e) {
            // 已在关闭流程中，忽略
        }
    }

    /**
     * 线程加固：只记录非常驻线程名（诊断用），并安装默认未捕获异常处理器把崩溃计入指标。
     * <p>刻意不把业务线程改成守护线程：WSS 上报线程一旦变守护，主线程退出时会连带静默杀掉上报，
     * 反而制造「客户端活着但不上报」的假象。</p>
     */
    public boolean hardenThreads(List<String> notes) {
        try {
            List<String> names = nonDaemonNames();
            if (!names.isEmpty()) {
                notes.add("非常驻（非守护）线程: " + String.join(", ", names));
            }
            Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
                ClientHealthMetrics.SINK.onCrash();
                System.err.println("[PTV-Security] 线程 " + thread.getName() + " 未捕获异常: " + error);
            });
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    private boolean identifySelf(List<String> notes) {
        try {
            ProcessHandle self = ProcessHandle.current();
            ProcessHandle.Info info = self.info();
            if (info.command().isPresent()) {
                notes.add("进程自省可用 pid=" + self.pid());
                return true;
            }
            return self.pid() > 0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Linux 下把 core dump 过滤位清零；其他平台或不可写时返回 false 并说明原因。 */
    private boolean disableCoreDump(List<String> notes) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("linux")) {
            notes.add("非 Linux 平台：core dump 控制不可达，未做处理");
            return false;
        }
        try {
            Path filter = Path.of("/proc/self/coredump_filter");
            Files.writeString(filter, "0");
            notes.add("已关闭 core dump 过滤 (/proc/self/coredump_filter=0)");
            return true;
        } catch (Exception e) {
            notes.add("core dump 过滤写入失败（权限或内核限制）: " + e.getMessage());
            return false;
        }
    }

    private int countNonDaemonThreads() {
        try {
            var threads = ManagementFactory.getThreadMXBean();
            return Math.max(0, threads.getThreadCount() - threads.getDaemonThreadCount());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static List<String> nonDaemonNames() {
        List<String> names = new ArrayList<>();
        try {
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (!thread.isDaemon()) names.add(thread.getName());
            }
        } catch (RuntimeException e) {
            // 忽略
        }
        return names;
    }
}