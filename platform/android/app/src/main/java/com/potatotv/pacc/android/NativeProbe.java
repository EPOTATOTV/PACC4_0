package com.potatotv.pacc.android;

/**
 * PACC Android 检测探针（Java 桥）。
 *
 * <p>java 层负责：
 * 1) 加载 NDK 库并声明 JNI 方法；
 * 2) 将 native 位掩码折算成统一 {@code DetectionEvent}
 *   （类型 / 严重度 / 0-100 预评分），供 {@code ptv-client} 上报 PTV；
 * 3) 绝不接触游戏服务器数据，仅本地采样。
 */
public final class NativeProbe {

    /** 与 native_probe.cpp 位掩码一致 */
    public static final int TF_NONE       = 0x0000;
    public static final int TF_MODULE     = 0x0001;
    public static final int TF_ROOT       = 0x0002;
    public static final int TF_DEBUGGER   = 0x0004;
    public static final int TF_ROM_TAMP   = 0x0008;
    public static final int TF_LIB_HIJACK = 0x0010;
    public static final int TF_SYS_TAMP   = 0x0020;

    static {
        try {
            System.loadLibrary("pacc_native_probe");
        } catch (UnsatisfiedLinkError e) {
            // 模块可选：加载失败时探针整体降级为空实现
        }
    }

    private NativeProbe() {}

    /** 综合扫描，返回位掩码。 */
    private static native int nativeScan();

    /** 返回诊断 JSON 行字符串。 */
    private static native String nativeDetect();

    /**
     * 运行一次完整检测，折算为统一事件结构。
     *
     * @return 归一化事件 JsonEntry：{@code score} 0-100，{@code severity} 类型，
     *         {@code hits} 命中标识集合
     */
    public static DetectionResult run() {
        try {
            int flags = nativeScan();
            return toResult(flags);
        } catch (UnsatisfiedLinkError | RuntimeException ex) {
            return new DetectionResult(0, "ok", new String[0]);
        }
    }

    private static DetectionResult toResult(int flags) {
        if (flags == TF_NONE) {
            return new DetectionResult(0, "ok", new String[0]);
        }
        java.util.List<String> hits = new java.util.ArrayList<>();
        if ((flags & TF_MODULE) != 0)     hits.add("inject_lib");
        if ((flags & TF_ROOT) != 0)       hits.add("root");
        if ((flags & TF_DEBUGGER) != 0)   hits.add("debugger");
        if ((flags & TF_ROM_TAMP) != 0)   hits.add("rom_tamper");
        if ((flags & TF_LIB_HIJACK) != 0) hits.add("lib_hijack");
        if ((flags & TF_SYS_TAMP) != 0)   hits.add("sys_tamper");

        int score;
        switch (hits.size()) {
            case 1:  score = 45; break;
            case 2:  score = 70; break;
            default: score = 100; break;
        }
        String severity = score >= 70 ? "high" : score > 0 ? "medium" : "ok";
        return new DetectionResult(score, severity, hits.toArray(new String[0]));
    }

    /** 统一检测结果。 */
    public record DetectionResult(int score, String severity, String[] hits) {}
}