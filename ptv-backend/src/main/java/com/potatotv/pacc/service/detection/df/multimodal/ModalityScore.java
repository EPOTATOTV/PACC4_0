package com.potatotv.pacc.service.detection.df.multimodal;

import java.util.List;

/**
 * DF §4.1.3 单模态评分结果：该模态独立的异常分与其可信度。
 *
 * @param modality   模态
 * @param present    该模态本次是否有数据（false 表示缺失，融合时被剔除并重新归一化权重）
 * @param score      异常分 0-1
 * @param confidence 置信度 0-1：由「实际出现的信号数 / 期望信号数」决定，信号缺失越多越不可信
 * @param evidence   判定依据（人类可读）
 */
public record ModalityScore(Modality modality, boolean present, double score, double confidence,
                            List<String> evidence) {

    /** 缺失模态的占位评分（score=0，conf=0）。 */
    public static ModalityScore absent(Modality modality) {
        return new ModalityScore(modality, false, 0.0, 0.0, List.of());
    }

    /** 加权贡献：缺失模态不贡献。 */
    public double weighted(double weight) {
        return present ? score * weight : 0.0;
    }
}