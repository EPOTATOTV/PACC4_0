package com.potatotv.paccclient.detection.samples;

/**
 * 方块操作采样（放置/破坏，{@code PlayerController} 插桩点）。
 *
 * @param timestampMillis 操作时间戳（毫秒）
 * @param place           true=放置，false=破坏
 * @param distance        操作距离（格）
 * @param sneaking        操作时是否潜行（Scaffold 判定）
 * @param faceAngle       视角与方块面法线夹角（度，Scaffold 判定）
 * @param radius          单次操作覆盖半径（格，Nuker 判定）
 */
public record BlockActionSample(long timestampMillis, boolean place, double distance,
                                boolean sneaking, double faceAngle, double radius) {
}