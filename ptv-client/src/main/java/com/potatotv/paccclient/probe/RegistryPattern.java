package com.potatotv.paccclient.probe;

/**
 * 注册表匹配模式（文档 §4.5，仅匹配键名是否存在，不读取键值）。
 *
 * @param key    注册表键路径（如 {@code HKCU\Software\Cheat Engine}）
 * @param weight 命中权重
 */
public record RegistryPattern(String key, int weight) {
}