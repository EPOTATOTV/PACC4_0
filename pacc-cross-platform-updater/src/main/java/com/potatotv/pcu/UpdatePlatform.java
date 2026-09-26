package com.potatotv.pcu;

import java.util.Locale;

/**
 * 更新涉及的平台标识，与设计文档 §4.7.2 的适配层一一对应。
 *
 * <p>字符串形式即服务端 {@code platform} 查询参数，服务端发布表里用的也是这套小写名。</p>
 */
public enum UpdatePlatform {

    WINDOWS("windows"),
    ANDROID("android"),
    IOS("ios"),
    HARMONY("harmony"),
    LINUX("linux"),
    MACOS("macos");

    private final String wire;

    UpdatePlatform(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    public static UpdatePlatform fromWire(String text) {
        if (text == null || text.isBlank()) {
            throw new PcuException("平台标识为空");
        }
        String low = text.trim().toLowerCase(Locale.ROOT);
        // 常见别名：鸿蒙在不同系统属性里写作 harmonyos / ohos
        if ("harmonyos".equals(low) || "ohos".equals(low)) {
            return HARMONY;
        }
        for (UpdatePlatform p : values()) {
            if (p.wire.equals(low)) {
                return p;
            }
        }
        throw new PcuException("未知平台标识：" + text);
    }

    /** 按当前运行环境推断平台（端侧未显式指定时的兜底）。 */
    public static UpdatePlatform current() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        // Android 的 os.name 同样是 "Linux"，只看 os.name 会把手机判成桌面 Linux，
        // 所以先认 JVM 自己报的名字（Dalvik / Android Runtime）。移动端本来就要求宿主
        // 显式传入平台（Bridge 是必填的），这里只是保证这个兜底不会静默判错。
        String vm = (System.getProperty("java.vm.name", "") + " "
                + System.getProperty("java.runtime.name", "")).toLowerCase(Locale.ROOT);
        if (vm.contains("dalvik") || vm.contains("android")) {
            return ANDROID;
        }
        if (os.contains("win")) {
            return WINDOWS;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            return MACOS;
        }
        if (os.contains("linux")) {
            return LINUX;
        }
        throw new PcuException("无法从 os.name 推断平台：" + System.getProperty("os.name"));
    }
}