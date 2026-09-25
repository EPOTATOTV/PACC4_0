package com.potatotv.paccclient.detection.samples;

/**
 * 位置采样（由游戏内存只读采样或客户端 tick 钩子推入）。
 *
 * @param timestampMillis 采样时间戳（毫秒）
 * @param position        玩家坐标
 * @param velocity        玩家速度向量（格每秒）
 * @param onGround        是否站在地面
 * @param sprinting       是否处于冲刺
 * @param fallDamageTaken 本次落地是否承受了坠落伤害（用于 NoFall 判定）
 */
public record PositionSample(long timestampMillis, Vec3 position, Vec3 velocity,
                             boolean onGround, boolean sprinting, boolean fallDamageTaken) {

    /** 便捷构造：速度为零向量。 */
    public static PositionSample of(long timestampMillis, Vec3 position, boolean onGround, boolean sprinting) {
        return new PositionSample(timestampMillis, position, new Vec3(0, 0, 0), onGround, sprinting, false);
    }
}