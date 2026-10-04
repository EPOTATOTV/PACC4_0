package com.potatotv.paccclient.spi;

import java.util.Map;

/**
 * 插件私有配置存储（文档 §2.2）。键值持久化到本地，插件卸载重装后仍在；
 * 写入只落本机磁盘，绝不出网（AGENTS.md 约束 2）。
 */
public interface PluginConfig {

    /** 取字符串值；不存在返回 {@code null}。 */
    String get(String key);

    /** 取字符串值；不存在返回 {@code defaultValue}。 */
    default String getOrDefault(String key, String defaultValue) {
        String v = get(key);
        return v == null ? defaultValue : v;
    }

    /** 写入并立即持久化。 */
    void put(String key, String value);

    /** 全部键值快照（只读）。 */
    Map<String, String> all();
}