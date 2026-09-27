package com.potatotv.pacc.service.detection.df.multimodal;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DF §4.1.3 多模态融合分析编排：五模态独立评分 → 按选定策略融合 → 可解释的融合结论。
 *
 * <p>职责边界：本服务只做「评分 + 融合」，不落库、不触发告警；供管理端做单次分析或联调。
 * 三种融合策略（早期 / 晚期 / 混合）通过 {@link FusionStrategy} 统一注入，调用方按名选择。</p>
 *
 * <p>缺失模态由调用方通过「不提供该模态载荷」表达：本服务对其产出 {@link ModalityScore#absent}
 * 占位，融合侧重新归一化其余模态权重——既不报错，也不把缺失误计为「正常」。</p>
 */
@Slf4j
@Service
public class MultimodalFusionService {

    private final Map<Modality, ModalityScorer> scorers = new EnumMap<>(Modality.class);
    private final Map<String, FusionStrategy> strategies = new LinkedHashMap<>();
    private final Map<Modality, Double> defaultWeights = new EnumMap<>(Modality.class);

    /**
     * @param scorerBeans 全部模态评分器（Spring 注入）
     * @param strategyBeans 全部融合策略（Spring 注入）
     * @param weightInput   输入模态默认权重
     * @param weightMemory  内存模态默认权重
     * @param weightNetwork 网络模态默认权重
     * @param weightBehavior 行为模态默认权重
     * @param weightImage   图像模态默认权重
     */
    public MultimodalFusionService(List<ModalityScorer> scorerBeans,
                                   List<FusionStrategy> strategyBeans,
                                   @Value("${pacc.df.multimodal.weight-input:1.0}") double weightInput,
                                   @Value("${pacc.df.multimodal.weight-memory:1.0}") double weightMemory,
                                   @Value("${pacc.df.multimodal.weight-network:0.8}") double weightNetwork,
                                   @Value("${pacc.df.multimodal.weight-behavior:1.0}") double weightBehavior,
                                   @Value("${pacc.df.multimodal.weight-image:0.8}") double weightImage) {
        for (ModalityScorer scorer : scorerBeans) {
            scorers.put(scorer.modality(), scorer);
        }
        for (FusionStrategy strategy : strategyBeans) {
            strategies.put(strategy.name(), strategy);
        }
        defaultWeights.put(Modality.INPUT, positive(weightInput));
        defaultWeights.put(Modality.MEMORY, positive(weightMemory));
        defaultWeights.put(Modality.NETWORK, positive(weightNetwork));
        defaultWeights.put(Modality.BEHAVIOR, positive(weightBehavior));
        defaultWeights.put(Modality.IMAGE, positive(weightImage));
    }

    /**
     * 执行一次多模态融合分析。
     *
     * @param strategyName    融合策略名（early / late / hybrid，大小写不敏感）；空则用 hybrid
     * @param inputs          各模态输入；未提供或载荷为空的模态按缺失处理
     * @param weightOverrides 权重覆盖（键为模态名，仅接受正数）；空则用配置默认权重
     * @return 融合结果（含各模态评分、生效权重、缺失模态）
     * @throws IllegalArgumentException 策略名不受支持
     */
    public FusedResult analyze(String strategyName, Map<Modality, ModalityInput> inputs,
                               Map<String, Double> weightOverrides) {
        List<ModalityScore> scores = new ArrayList<>(Modality.values().length);
        for (Modality modality : Modality.values()) {
            ModalityInput input = inputs == null ? null : inputs.get(modality);
            ModalityScorer scorer = scorers.get(modality);
            if (input == null || input.absent() || scorer == null) {
                scores.add(ModalityScore.absent(modality));
            } else {
                scores.add(scorer.score(input));
            }
        }

        Map<Modality, Double> weights = new EnumMap<>(defaultWeights);
        if (weightOverrides != null) {
            for (Map.Entry<String, Double> e : weightOverrides.entrySet()) {
                Modality modality = Modality.fromKey(e.getKey());
                Double value = e.getValue();
                if (modality != null && value != null && value > 0 && Double.isFinite(value)) {
                    weights.put(modality, value);
                }
            }
        }

        FusionStrategy strategy = resolve(strategyName);
        FusedResult result = strategy.fuse(scores, weights);
        log.info("多模态融合分析 strategy={} fused={} verdict={} 缺失={}",
                result.strategy(), result.fusedScore(), result.verdict(), result.missingModalities());
        return result;
    }

    /** 默认权重视图（供管理端展示可用权重基线）。 */
    public Map<String, Double> defaultWeightsView() {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<Modality, Double> e : defaultWeights.entrySet()) {
            out.put(e.getKey().key(), e.getValue());
        }
        return out;
    }

    /** 可用策略名列表。 */
    public List<String> availableStrategies() {
        return List.copyOf(strategies.keySet());
    }

    /** 解析策略：空按 hybrid，未知抛 {@link IllegalArgumentException}。 */
    private FusionStrategy resolve(String strategyName) {
        String key = (strategyName == null || strategyName.isBlank())
                ? "hybrid"
                : strategyName.trim().toLowerCase(Locale.ROOT);
        FusionStrategy strategy = strategies.get(key);
        if (strategy == null) {
            throw new IllegalArgumentException("不支持的融合策略：" + strategyName
                    + "（可用：" + String.join("/", strategies.keySet()) + "）");
        }
        return strategy;
    }

    private static double positive(double v) {
        return Double.isFinite(v) && v > 0 ? v : 0.0;
    }
}