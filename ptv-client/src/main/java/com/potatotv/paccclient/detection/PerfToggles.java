package com.potatotv.paccclient.detection;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 检测性能开关（文档 §10.1 / 三层架构 §7 注意事项 7：所有检测组件必须有性能开关，
 * 可在管理端动态调整）。
 *
 * <p>默认策略分两档：既有开关与系统层增强默认开启；三层架构里开销大、且依赖外部条件的
 * <b>网络代理层与屏幕层默认关闭</b>（PACC_NET_PROXY_ENABLED / PACC_VISION_ENABLED 显式置
 * {@code true}/{@code 1}/{@code on} 才启用）——文档 §7 注意事项 7 明确规定「默认只开系统层增强」。</p>
 *
 * <p>可用环境变量 {@code PACC_FEATURE_COLLECTION} / {@code PACC_LOCAL_AI} /
 * {@code PACC_BRUTE_FORCE} / {@code PACC_STEALTH_PROBES} / {@code PACC_SYSTEM_SCANNERS} /
 * {@code PACC_SYSTEM_DEEP_ENABLED} / {@code PACC_NET_PROXY_ENABLED} / {@code PACC_VISION_ENABLED}
 * 置为 {@code false}/{@code 0}/{@code off} 在启动时关闭。管理端 / 远程配置可通过
 * {@code LocalControlServer} 调用 {@link #set(String, boolean)}
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
    /** 系统专项检测器开关（DF Alpha 1.0.0 §4，进程/模块/驱动等 9 个 Scanner）。 */
    public static final String SYSTEM_SCANNERS = "system_scanners";
    /** 系统进程层增强开关（三层架构 §4：DLL 签名 / 注入 / 未签名 EXE / 内核回调；默认开启）。 */
    public static final String SYSTEM_DEEP = "system_deep";
    /** 网络代理层开关（三层架构 §2：SOCKS5 / Bedrock 中继 / 协议解析；默认关闭）。 */
    public static final String NET_PROXY = "net_proxy";
    /** 屏幕与输入层开关（三层架构 §3：截屏视觉分析 / 输入时序 / 板载宏；默认关闭）。 */
    public static final String VISION = "vision";

    private static final Map<String, String> ENV = Map.of(
            FEATURE_COLLECTION, "PACC_FEATURE_COLLECTION",
            LOCAL_AI, "PACC_LOCAL_AI",
            BRUTE_FORCE, "PACC_BRUTE_FORCE",
            STEALTH_PROBES, "PACC_STEALTH_PROBES",
            SYSTEM_SCANNERS, "PACC_SYSTEM_SCANNERS",
            SYSTEM_DEEP, "PACC_SYSTEM_DEEP_ENABLED",
            NET_PROXY, "PACC_NET_PROXY_ENABLED",
            VISION, "PACC_VISION_ENABLED");

    /** 默认关闭的开关（文档 §7 注意事项 7：默认只开系统层增强）。 */
    private static final Set<String> DEFAULT_OFF = Set.of(NET_PROXY, VISION);

    private static final ConcurrentHashMap<String, Boolean> STATE = new ConcurrentHashMap<>();

    private PerfToggles() {
    }

    /** 开关是否开启；未显式设置时取环境变量默认（三层新增项缺省关闭，其余缺省开启）。 */
    public static boolean enabled(String name) {
        Boolean v = STATE.get(name);
        if (v != null) return v;
        return envEnabled(ENV.get(name), !DEFAULT_OFF.contains(name));
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

    private static boolean envEnabled(String envName, boolean defaultOn) {
        if (envName == null) return defaultOn;
        String raw = System.getenv(envName);
        if (raw == null || raw.isBlank()) return defaultOn;
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.equals("false") || v.equals("0") || v.equals("off") || v.equals("no")) return false;
        if (v.equals("true") || v.equals("1") || v.equals("on") || v.equals("yes")) return true;
        return defaultOn;
    }
}