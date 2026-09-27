package com.potatotv.pcu.platform;

import com.potatotv.pcu.PcuException;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Linux 适配（设计文档 §4.7.2）：PACC 以 systemd 服务形式常驻，停/起/重启都交给
 * {@code systemctl}。
 *
 * <p>重启走 {@code systemctl restart} 而不是「stop 再 start」：让 systemd 自己处理
 * 依赖顺序与失败清理，也避免端侧在两步之间留下一个「既没停也没起」的空档。</p>
 */
public final class LinuxPlatformAdapter extends ProcessPlatformAdapter {

    /** 默认服务单元名；实际部署若不同，用带单元名的构造函数覆盖。 */
    public static final String DEFAULT_UNIT = "pacc";

    private final String unit;

    public LinuxPlatformAdapter(Path installDir, Path tempDir) {
        this(installDir, tempDir, DEFAULT_UNIT);
    }

    public LinuxPlatformAdapter(Path installDir, Path tempDir, String unit) {
        super(installDir, tempDir, new Commands(
                List.of("systemctl", "stop", requireUnit(unit)),
                List.of("systemctl", "start", requireUnit(unit)),
                Duration.ofSeconds(60), Duration.ofSeconds(60)));
        this.unit = unit;
    }

    @Override
    public void restartPacc() {
        int exit = runCommand(List.of("systemctl", "restart", unit), Duration.ofSeconds(90));
        if (exit != 0) {
            throw new PcuException("systemctl restart " + unit + " 返回退出码 " + exit);
        }
    }

    private static String requireUnit(String unit) {
        Objects.requireNonNull(unit, "unit");
        if (unit.isBlank() || unit.startsWith("-")) {
            // 以 '-' 开头的单元名会被 systemctl 当成选项，属于命令行注入
            throw new PcuException("非法的 systemd 单元名：" + unit);
        }
        return unit;
    }
}