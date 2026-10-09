package com.potatotv.paccclient.plugin;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 插件加载选项（文档 §2.3 沙箱约束 5、§2.5 版本化）。
 *
 * @param devMode             开发者模式：允许加载未签名插件。生产必须为 {@code false}
 * @param trustedFingerprints 受信任签名者证书的 SHA-256 指纹（小写十六进制）
 * @param hostApiVersion      宿主插件 API 版本
 * @param hostBuild           宿主构建号，用于 {@code minClientBuild} 判断
 */
public record PluginLoadOptions(
        boolean devMode,
        Set<String> trustedFingerprints,
        int hostApiVersion,
        int hostBuild) {

    public PluginLoadOptions {
        trustedFingerprints = trustedFingerprints == null ? Set.of() : Set.copyOf(trustedFingerprints);
    }

    /**
     * 默认选项：从环境变量读取开发者模式与信任指纹。
     *
     * <ul>
     *   <li>{@code PACC_PLUGIN_DEV} 非空即开发者模式（允许未签名插件）；</li>
     *   <li>{@code PACC_PLUGIN_TRUST} 逗号分隔的 SHA-256 指纹列表。</li>
     * </ul>
     *
     * <p>生产环境不内置任何公钥：真正的 POTATOTV 证书指纹由构建/配置注入。
     * 未配置信任指纹且非开发者模式时，任何插件都不会被加载 —— 这是「默认拒绝」的安全取向。</p>
     */
    public static PluginLoadOptions defaults() {
        return new PluginLoadOptions(devModeFromEnv(), trustedFingerprintsFromEnv(),
                com.potatotv.paccclient.spi.PluginMetadata.CURRENT_API_VERSION, Integer.MAX_VALUE);
    }

    private static boolean devModeFromEnv() {
        String env = System.getenv("PACC_PLUGIN_DEV");
        return env != null && !env.isBlank();
    }

    private static Set<String> trustedFingerprintsFromEnv() {
        String env = System.getenv("PACC_PLUGIN_TRUST");
        if (env == null || env.isBlank()) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String part : env.split(",")) {
            String fp = part.trim().toLowerCase();
            if (!fp.isEmpty()) out.add(fp);
        }
        return out;
    }
}