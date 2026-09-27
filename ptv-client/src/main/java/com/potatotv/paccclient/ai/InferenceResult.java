package com.potatotv.paccclient.ai;

import com.potatotv.paccclient.detection.FeatureVector;

import java.util.Map;

/**
 * 端侧 AI 推理结果（文档 §2.1）：分数 + 特征贡献度 + 来源 + 延迟，支持人工复核与降级溯源。
 *
 * <p>来源取值：{@link #SOURCE_MODEL} 正常模型判定；{@link #SOURCE_FALLBACK} 未加载/推理超时等
 * 降级为规则回退；{@link #SOURCE_STALE} 模型过旧（>30 天）降权后的判定。</p>
 *
 * @param score         归一化分数（约 0-1）
 * @param contributions 特征贡献度（按绝对贡献降序）
 * @param source        判定来源
 * @param latencyMs     推理耗时（毫秒）
 */
public record InferenceResult(double score, Map<String, Double> contributions, String source, long latencyMs) {

    public static final String SOURCE_MODEL = "model";
    public static final String SOURCE_FALLBACK = "fallback";
    public static final String SOURCE_STALE = "stale";

    /**
     * 规则回退结果（文档 §2.1.4「模型加载失败 → 回退到规则引擎判定」）。
     * <p>无模型可用时不做任何 ML 推理，仅给出一个轻量启发分：特征向量中非零维度占比
     * （空向量记 0），真正的风险分仍由端侧规则引擎给出的 {@code clientRiskScore} 承担。</p>
     */
    public static InferenceResult fallback(FeatureVector fv, long latencyMs) {
        double score = 0.0;
        if (fv != null && fv.size() > 0) {
            int nonZero = 0;
            for (double v : fv.asMap().values()) {
                if (v != 0.0) nonZero++;
            }
            score = (double) nonZero / fv.size();
        }
        return new InferenceResult(score, Map.of(), SOURCE_FALLBACK, latencyMs);
    }
}