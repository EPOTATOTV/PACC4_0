package com.potatotv.pcu.platform;

import com.potatotv.pcu.PcuException;
import com.potatotv.pcu.PlatformAdapter;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * 用 {@link ProcessBuilder} 驱动外部命令的适配层基类（设计文档 §4.7.2 的 Windows / Linux / macOS）。
 *
 * <p>桌面平台的差异只在「跑哪条命令」：停服务一条、起服务一条。其余动作（安装目录、临时目录、
 * 存储空间、通知、存储权限）在这一层统一实现，子类只负责给出命令。</p>
 */
public abstract class ProcessPlatformAdapter implements PlatformAdapter {

    private static final Logger LOG = Logger.getLogger(ProcessPlatformAdapter.class.getName());

    /**
     * 一组平台命令。
     *
     * @param startTimeout 启动命令的等待上限；为 {@code null} 表示「拉起即返回、不等退出」，
     *                     用于 PACC 这种常驻客户端进程——等它退出会一直挂着。
     */
    protected record Commands(List<String> stop, List<String> start,
                              Duration stopTimeout, Duration startTimeout) {

        protected Commands {
            Objects.requireNonNull(stop, "stop");
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(stopTimeout, "stopTimeout");
            if (stop.isEmpty() != start.isEmpty()) {
                // 只给一半命令会把「停得掉、起不来」这种事一路拖到线上
                throw new PcuException("stop/start 命令必须同时给出或同时留空");
            }
        }
    }

    private final Path installDir;
    private final Path tempDir;
    private final Commands commands;

    protected ProcessPlatformAdapter(Path installDir, Path tempDir, Commands commands) {
        this.installDir = Objects.requireNonNull(installDir, "installDir").toAbsolutePath().normalize();
        this.tempDir = Objects.requireNonNull(tempDir, "tempDir").toAbsolutePath().normalize();
        this.commands = Objects.requireNonNull(commands, "commands");
    }

    @Override
    public void stopPacc() {
        if (commands.stop().isEmpty()) {
            LOG.fine("未配置停止命令，跳过停服务");
            return;
        }
        int exit = runCommand(commands.stop(), commands.stopTimeout());
        if (exit != 0) {
            // 最常见的原因是「本来就没在跑」，不该因此让整条更新链路失败
            LOG.warning(() -> "停止命令返回退出码 " + exit + "，按服务已停止继续：" + commands.stop());
        }
    }

    @Override
    public void startPacc() {
        if (commands.start().isEmpty()) {
            throw new PcuException("未配置启动命令，无法拉起 PACC");
        }
        if (commands.startTimeout() == null) {
            startDetached();
            return;
        }
        int exit = runCommand(commands.start(), commands.startTimeout());
        if (exit != 0) {
            throw new PcuException("启动命令返回退出码 " + exit + "：" + commands.start());
        }
    }

    @Override
    public Path getInstallDir() {
        return installDir;
    }

    @Override
    public Path getTempDir() {
        return tempDir;
    }

    @Override
    public CompletableFuture<Boolean> requestStoragePermission() {
        // 桌面平台没有运行期存储权限这一说
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public void showUpdateNotification(String title, String message) {
        DesktopNotifier.notify(title, message);
    }

    @Override
    public boolean hasEnoughSpace(long requiredBytes) {
        return PlatformSupport.hasEnoughSpace(tempDir, requiredBytes);
    }

    /** 常驻进程：拉起后立刻返回，输出丢弃，不让客户端日志串到宿主终端。 */
    private void startDetached() {
        ProcessBuilder builder = new ProcessBuilder(commands.start())
                .directory(installDir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            builder.start();
            LOG.info(() -> "已拉起 PACC：" + commands.start());
        } catch (IOException e) {
            throw new PcuException("启动 PACC 失败：" + commands.start(), e);
        }
    }

    /** 同步执行命令并返回退出码；超时强杀后抛异常。 */
    protected final int runCommand(List<String> command, Duration timeout) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(installDir.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD);
        try {
            Process process = builder.start();
            if (process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
            process.destroyForcibly();
            throw new PcuException("命令超时（" + timeout.toSeconds() + "s）：" + command);
        } catch (IOException e) {
            throw new PcuException("执行命令失败：" + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PcuException("执行命令被打断：" + command, e);
        }
    }
}