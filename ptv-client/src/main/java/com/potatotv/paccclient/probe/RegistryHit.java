package com.potatotv.paccclient.probe;

/**
 * 注册表键命中（文档 §4.5）。
 *
 * @param key    命中的键路径
 * @param weight 命中权重
 */
public record RegistryHit(String key, int weight) {
}