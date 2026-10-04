package com.potatotv.paccclient.spi;

import java.util.Objects;

/**
 * 插件元信息（文档 §2.5）。由插件作者在 {@code META-INF/pacc-plugin.json} 中声明，
 * 加载器读取后用于版本兼容判断与展示。
 *
 * @param id             反向域名唯一标识，如 {@code net.potatotv.ce-detector}
 * @param name           显示名
 * @param version        语义化版本，如 {@code 1.2.0}
 * @param author         作者
 * @param description    描述（可空）
 * @param apiVersion     依赖的 PACC 插件 API 版本（当前为 {@value #CURRENT_API_VERSION}）
 * @param minClientBuild 最低客户端构建号；宿主构建号低于此值时不加载
 */
public record PluginMetadata(
        String id,
        String name,
        String version,
        String author,
        String description,
        int apiVersion,
        int minClientBuild) {

    /** 当前宿主支持的插件 API 版本（文档 §2.5：当前=1）。 */
    public static final int CURRENT_API_VERSION = 1;

    public PluginMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        if (id.isBlank()) throw new IllegalArgumentException("插件 id 不能为空");
        if (apiVersion < 1) throw new IllegalArgumentException("apiVersion 必须为正整数");
    }

    /**
     * 是否与给定宿主兼容。
     *
     * <p>API 版本只做「不超过宿主」判断：新增方法一律带 default 实现（minor 递增），旧插件仍可用；
     * 插件声明了比宿主更高的版本说明它依赖尚未提供的能力，拒绝加载而不是降级运行。</p>
     */
    public boolean compatibleWith(int hostApiVersion, int hostBuild) {
        return apiVersion <= hostApiVersion && minClientBuild <= hostBuild;
    }
}