package com.potatotv.paccclient.probe;

import java.util.Locale;

/**
 * 操作系统信息摘要（脱敏，仅名称/版本/架构）。
 *
 * @param name    操作系统名（{@code os.name}）
 * @param version 版本（{@code os.version}）
 * @param arch    架构（{@code os.arch}）
 */
public record OsInfo(String name, String version, String arch) {

    /** 读取当前 JVM 所在系统信息。 */
    public static OsInfo current() {
        return new OsInfo(
                System.getProperty("os.name", ""),
                System.getProperty("os.version", ""),
                System.getProperty("os.arch", ""));
    }

    public boolean isWindows() {
        return name != null && name.toLowerCase(Locale.ROOT).contains("win");
    }

    public boolean isLinux() {
        return name != null && name.toLowerCase(Locale.ROOT).contains("linux");
    }

    public boolean isMac() {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return n.contains("mac") || n.contains("darwin");
    }

    /** 单行摘要（上报 / 事件 osInfo 字段）。 */
    public String summary() {
        return name + " " + version + " (" + arch + ")";
    }
}