package com.potatotv.pcu.platform;

import com.potatotv.pcu.PcuException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * macOS 适配（设计文档 §4.7.2）：用 {@code open} 拉起 PACC（.app 包或可直接执行的
 * JAR 都行），用 {@code pkill -f} 按启动目标路径结束对应进程。
 *
 * <p>{@code pkill} 在「没有匹配到进程」时返回 1，基类把它当告警处理，不影响更新流程。</p>
 */
public final class MacOsPlatformAdapter extends ProcessPlatformAdapter {

    public MacOsPlatformAdapter(Path installDir, Path tempDir) {
        this(installDir, tempDir, installDir);
    }

    /**
     * @param launchTarget 交给 {@code open} 的目标，同时作为 {@code pkill -f} 的匹配串；
     *                     默认取安装目录，装了 .app 包时传那个包的路径更准。
     */
    public MacOsPlatformAdapter(Path installDir, Path tempDir, Path launchTarget) {
        super(installDir, tempDir, new Commands(
                List.of("pkill", "-f", requireTarget(launchTarget)),
                List.of("open", launchTarget.toString()),
                Duration.ofSeconds(15),
                // macOS 上 PACC 同样是常驻进程，拉起即返回
                null));
    }

    private static String requireTarget(Path launchTarget) {
        Objects.requireNonNull(launchTarget, "launchTarget");
        String raw = launchTarget.toString();
        if (raw.startsWith("-")) {
            // 以 '-' 开头会被 pkill 当成选项，必须在绝对化之前拦下（绝对化后开头必然是 '/'）
            throw new PcuException("非法的启动目标路径：" + raw);
        }
        return launchTarget.toAbsolutePath().normalize().toString();
    }
}