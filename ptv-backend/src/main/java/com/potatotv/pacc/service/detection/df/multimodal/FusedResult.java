package com.potatotv.pacc.service.detection.df.multimodal;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DF §4.1.3 融合结果：某次多模态分析的完整可解释输出。
 *
 * @param strategy         使用的融合策略：{@code early} / {@code late} / {@code hybrid}
 * @param fusedScore       融合判定分 0-1
 * @param verdict          结论：{@code SAFE} / {@code SUSPICIOUS} / {@code CHEAT} / {@code CRITICAL}
 * @param weightsUsed      实际生效权重（已按出现模态重新归一化，仅含出现的模态）
 * @param contributions    各模态对融合分的加权贡献（0-1，缺失模态不出现）
 * @param modalityScores   五个模态（含缺失占位）的独立评分
 * @param missingModalities 本次缺失的模态（权重已按其余模态重新归一化）
 */
public record FusedResult(
        String strategy,
        double fusedScore,
        String verdict,
        Map<Modality, Double> weightsUsed,
        Map<Modality, Double> contributions,
        List<ModalityScore> modalityScores,
        List<Modality> missingModalities
) {

    /** 取指定模态的评分；不存在返回缺失占位。 */
    public ModalityScore scoreOf(Modality modality) {
        for (ModalityScore s : modalityScores) {
            if (s.modality() == modality) {
                return s;
            }
        }
        return ModalityScore.absent(modality);
    }

    /**
     * 权重重新归一化：只保留出现（有数据）的模态，权重按比例缩放到和为 1。
     *
     * <p>缺失模态不是错误——重新归一化即可优雅降级；若出现的模态权重全为 0，
     * 则在出现模态间等权（保证融合分不会被整体缩放到 0）。</p>
     */
    static Map<Modality, Double> renormalize(Map<Modality, Double> weights,
                                             Collection<ModalityScore> scores) {
        Map<Modality, Double> out = new LinkedHashMap<>();
        double total = 0;
        int present = 0;
        for (ModalityScore s : scores) {
            if (!s.present()) {
                continue;
            }
            present++;
            total += Math.max(0.0, weights.getOrDefault(s.modality(), 0.0));
        }
        if (present == 0) {
            return out;
        }
        if (total <= 0) {
            for (ModalityScore s : scores) {
                if (s.present()) {
                    out.put(s.modality(), 1.0 / present);
                }
            }
            return out;
        }
        for (ModalityScore s : scores) {
            if (s.present()) {
                out.put(s.modality(), Math.max(0.0, weights.getOrDefault(s.modality(), 0.0)) / total);
            }
        }
        return out;
    }

    /** 结论阈值：可疑 / 作弊 / 高危。 */
    static String verdictOf(double score) {
        if (score >= 0.85) {
            return "CRITICAL";
        }
        if (score >= 0.65) {
            return "CHEAT";
        }
        if (score >= 0.4) {
            return "SUSPICIOUS";
        }
        return "SAFE";
    }

    /** 各模态加权贡献（缺失模态不出现）。 */
    static Map<Modality, Double> contributions(List<ModalityScore> scores, Map<Modality, Double> weights) {
        Map<Modality, Double> out = new LinkedHashMap<>();
        for (ModalityScore s : scores) {
            if (s.present()) {
                out.put(s.modality(), round(s.score() * weights.getOrDefault(s.modality(), 0.0)));
            }
        }
        return out;
    }

    /** 缺失模态列表。 */
    static List<Modality> missing(List<ModalityScore> scores) {
        return scores.stream().filter(s -> !s.present()).map(ModalityScore::modality).toList();
    }

    static double round(double v) {
        return Math.round(v * 1_000_000.0) / 1_000_000.0;
    }
}