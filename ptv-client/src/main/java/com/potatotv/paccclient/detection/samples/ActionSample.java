package com.potatotv.paccclient.detection.samples;

/**
 * 泛化界面动作采样（进食 / 开箱 / 背包管理 / 护甲穿戴），覆盖文档 §3.2 中依赖界面时序的作弊类型。
 *
 * @param timestampMillis 动作时间戳（毫秒）
 * @param kind            动作种类
 * @param durationMillis  动作耗时（ms）
 * @param amount          动作涉及的数量（物品数等，一次动作为 1）
 */
public record ActionSample(long timestampMillis, ActionKind kind, double durationMillis, double amount) {

    /** 界面动作种类。 */
    public enum ActionKind {
        /** 进食。 */
        EAT,
        /** 开箱取物。 */
        CHEST,
        /** 背包管理。 */
        INVENTORY,
        /** 护甲穿戴。 */
        ARMOR
    }
}