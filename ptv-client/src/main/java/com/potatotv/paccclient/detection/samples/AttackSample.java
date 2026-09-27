package com.potatotv.paccclient.detection.samples;

/**
 * 攻击采样（{@code Player.attack()} 插桩点）。
 *
 * @param timestampMillis 攻击时间戳（毫秒）
 * @param distance        攻击距离（格）
 * @param yaw             攻击瞬间偏航角（度）
 * @param pitch           攻击瞬间俯仰角（度）
 * @param critical        是否暴击
 * @param hit             是否命中实体
 * @param airborne        攻击时是否离地（用于「不可能暴击」判定）
 * @param targetId        被攻击实体 ID（-1 表示未命中）
 * @param blocked         攻击后是否立即恢复格挡（AutoBlock 判定）
 */
public record AttackSample(long timestampMillis, double distance, double yaw, double pitch,
                           boolean critical, boolean hit, boolean airborne, int targetId, boolean blocked) {
}