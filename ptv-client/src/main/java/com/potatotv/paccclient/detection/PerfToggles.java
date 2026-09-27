package com.potatotv.paccclient.detection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v5.2 检测性能开关（文档 §10.1：所有检测组件必须有性能开关，可在管理端动态调整）。
 *
 * <p>默认全部开启；可用环境变量 {@code PACC_FEATURE_COLLECTION} / {@code PACC_LOCAL_AI} /
 * {@code PACC_BRUTE_FORCE} / {@code PACC_STEALTH_PROBES} 置为 {@code false}/{@code 0}/{@code off}
 * 在启动时关闭。管理端 / 远程配置可通过 {@code LocalControlServer} 调用 {@link #set(String, boolean)}
 * 在运行时翻转（沿用现有远程配置下发通道，无需新增协议）。</p>
 */
public final class PerfToggles {

    /** 特征采集开关（文档 178 维采集）。 */
    public static final String FEATURE_COLLECTION = "feature_collection";
    /** 端侧 AI 推理开关（LocalAiModel）。 */
    public static final String LOCAL_AI = "local_ai";
    /** 暴力外挂时序模型开关（ClickInterval / Trajectory / Temporal）。 */
    public static final String BRUTE_FORCE = "brute_force";
    /** 隐身探针开关（§4，扫描成本较高）。 */
    public static final String STEALTH_PROBES = "stealth_probes";

    private static final Map<String, String> ENV = Map.of(
            FEATURE_COLLECTION, "PACC_FEATURE_COLLECTION",
            LOCAL_AI, "PACC_LOCAL_AI",
            BRUTE_FORCE, "PACC_BRUTE_FORCE",
            STEALTH_PROBES, "PACC_STEALTH_PROBES");

    private static final ConcurrentHashMap<String, Boolean> STATE = new ConcurrentHashMap<>();

    private PerfToggles() {
    }

    /** 开关是否开启；未显式设置时取环境变量默认（缺省 true）。 */
    public static boolean enabled(String name) {
        Boolean v = STATE.get(name);
        if (v != null) return v;
        return envEnabled(ENV.get(name));
    }

    /** 运行时翻转（管理端/远程配置路径）。 */
    public static void set(String name, boolean on) {
        STATE.put(name, on);
    }

    /** 全部开关快照（含未显式设置项的环境变量默认值）。 */
    public static Map<String, Boolean> snapshot() {
        Map<String, Boolean> out = new LinkedHashMap<>();
        for (String key : ENV.keySet()) out.put(key, enabled(key));
        return out;
    }

    private static boolean envEnabled(String envName) {
        if (envName == null) return true;
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return true;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("false") || v.equals("0") || v.equals("off") || v.equals("no"));
    }
}