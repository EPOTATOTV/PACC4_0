package com.potatotv.pacc.service.detection.df.multimodal;

import java.util.List;
import java.util.Map;

/**
 * DF §4.1.3 融合策略：三种策略实现同一接口，调用方按 {@link #name()} 选择，互不影响单模态语义。
 *
 * <ul>
 *   <li>{@code early} 早期融合：各模态特征（加权分）拼接后统一输入一个 AI 判别模型；</li>
 *   <li>{@code late} 晚期融合：各模态独立评分后按权重加权求和；</li>
 *   <li>{@code hybrid} 混合融合：关键模态早期融合，辅助模态晚期融合，再按比例合成。</li>
 * </ul>
 *
 * <p>三种策略对「缺失模态」的处理一致：把该模态从权重中剔除并重新归一化，而不是报错或补 0 拉低总分。</p>
 */
public interface FusionStrategy {

    /** 策略名（对外键：early / late / hybrid）。 */
    String name();

    /**
     * 执行融合。
     *
     * @param scores  五个模态的评分（含缺失占位）
     * @param weights 各模态配置权重（未归一化）
     */
    FusedResult fuse(List<ModalityScore> scores, Map<Modality, Double> weights);
}