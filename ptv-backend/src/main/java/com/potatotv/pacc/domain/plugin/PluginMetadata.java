package com.potatotv.pacc.domain.plugin;

import java.util.Set;

/**
 * 检测插件元数据：由插件实现通过 {@link DetectionPlugin#getMetadata()} 声明。
 *
 * @param pluginId     插件唯一标识（与插件市场 {@code t_plugin.plugin_id} 对齐可选）
 * @param name         展示名
 * @param version      语义化版本
 * @param author       作者
 * @param description  说明
 * @param declaredApis 该插件声明需要使用的沙箱 API 白名单令牌（如 {@code feature:read}、{@code result:emit}）
 */
public record PluginMetadata(String pluginId, String name, String version, String author,
                             String description, Set<String> declaredApis) {

    public PluginMetadata {
        declaredApis = declaredApis == null ? Set.of() : Set.copyOf(declaredApis);
    }
}