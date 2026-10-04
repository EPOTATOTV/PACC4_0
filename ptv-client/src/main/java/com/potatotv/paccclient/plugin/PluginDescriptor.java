package com.potatotv.paccclient.plugin;

import java.util.Map;
import java.util.Objects;

/**
 * 从 {@code META-INF/pacc-plugin.json} 解析出的插件描述（文档 §2.4 步骤 3）。
 *
 * <p>元信息与入口类都在这个文件里；加载器据此创建 {@code PluginMetadata} 并实例化
 * {@code entryClass}。JSON 结构：</p>
 * <pre>
 * {
 *   "id": "net.potatotv.ce-detector",
 *   "name": "CE Detector",
 *   "version": "1.0.0",
 *   "author": "POTATOTV",
 *   "description": "Cheat Engine 专杀",
 *   "apiVersion": 1,
 *   "minClientBuild": 0,
 *   "entryClass": "net.potatotv.ce.CePlugin"
 * }
 * </pre>
 */
public record PluginDescriptor(
        String id,
        String name,
        String version,
        String author,
        String description,
        int apiVersion,
        int minClientBuild,
        String entryClass) {

    public PluginDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entryClass, "entryClass");
        if (id.isBlank()) throw new IllegalArgumentException("插件 id 不能为空");
        // id 会被拼进插件配置目录，限定字符集以防路径穿越（.. / 分隔符 / 绝对路径）
        if (!id.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("插件 id 只允许字母、数字与 . _ -：" + id);
        }
        if (entryClass.isBlank()) throw new IllegalArgumentException("入口类不能为空");
    }

    /** 从已解析的 JSON 对象构造；字段缺失或类型不符抛 {@link IllegalArgumentException}。 */
    public static PluginDescriptor fromJson(Map<String, Object> json) {
        return new PluginDescriptor(
                str(json, "id"),
                str(json, "name"),
                str(json, "version"),
                str(json, "author"),
                json.get("description") instanceof String s ? s : null,
                asInt(json.get("apiVersion"), 1),
                asInt(json.get("minClientBuild"), 0),
                str(json, "entryClass"));
    }

    private static String str(Map<String, Object> json, String key) {
        Object v = json.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException("插件描述缺少字段：" + key);
        }
        return s;
    }

    private static int asInt(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        return fallback;
    }
}