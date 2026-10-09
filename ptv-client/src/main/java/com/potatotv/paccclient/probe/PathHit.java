package com.potatotv.paccclient.probe;

/**
 * 文件路径命中（文档 §4.4）。
 *
 * @param path   命中路径
 * @param weight 命中权重
 */
public record PathHit(String path, int weight) {
}