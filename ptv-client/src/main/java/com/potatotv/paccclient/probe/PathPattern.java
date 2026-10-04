package com.potatotv.paccclient.probe;

/**
 * 文件路径匹配模式（文档 §4.4）。
 *
 * @param glob   通配模式（{@code *} / {@code ?}，路径分隔符统一按 {@code /} 比较）
 * @param weight 命中权重（0-100 打分体系）
 */
public record PathPattern(String glob, int weight) {
}