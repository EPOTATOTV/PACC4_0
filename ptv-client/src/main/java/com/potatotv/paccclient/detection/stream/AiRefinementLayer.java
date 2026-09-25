package com.potatotv.paccclient.detection.stream;

import com.potatotv.paccclient.ai.InferenceResult;
import com.potatotv.paccclient.ai.LocalAiModel;
import com.potatotv.paccclient.detection.FeatureVector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * DF §4.1.1 AI 精判层：只在快速规则层存疑（{@link FastRuleLayer.Verdict#INCONCLUSIVE}）时运行
 * （目标 &lt;10ms）。
 *
 * <p>输入为增量特征累加器的窗口均值快照（既有 {@link FeatureVector} 容器，键序即
 * {@code FeatureSchema}），直接交给既有端侧推理层 {@link LocalAiModel}；模型未加载或推理回退
 * 时不抬升分数（保持规则分），因此 AI 不可用绝不放大误报，也绝不阻断检测链路。</p>
 */
public final class AiRefinementLayer {

    /** AI 精判得分达到该值即视为可疑。 */
    public static final double SUSPICIOUS_SCORE = 0.5;
    /** 贡献度进入判定依据的门限（按绝对值）。 */
    private static final double CONTRIBUTION_MIN = 0.05;
    /** 判定依据最多保留的贡献项数。 */
    private static final int MAX_REASONS = 3;

    private final LocalAiModel aiModel;

    /**
     * @param aiModel 端侧模型（由 {@code ModelRepository} 装载）；{@code null} 表示无模型运行
     */
    public AiRefinementLayer(LocalAiModel aiModel) {
        this.aiModel = aiModel == null ? new LocalAiModel() : aiModel;
    }

    /** 精判结果。 */
    public record AiResult(double score, boolean suspicious, List<String> reasons) {
    }

    /**
     * 精判一条事件。
     *
     * @param event     待精判事件
     * @param snapshot  增量特征窗口均值快照（可为 {@code null}）
     * @param window    该事件的滑动窗口（可为 {@code null}，提供窗口信号水平/波动与到达间隔）
     * @param ruleScore 快速规则层得分 0-1
     */
    public AiResult refine(StreamEvent event, FeatureVector snapshot, SlidingWindow window, double ruleScore) {
        double base = FastRuleLayer.clamp01(ruleScore);
        InferenceResult ir = aiModel.infer(snapshot == null ? new FeatureVector() : snapshot);
        if (InferenceResult.SOURCE_FALLBACK.equals(ir.source())) {
            // 模型未加载/推理回退：不抬升分数（文档 §2.1.4），只留痕
            return new AiResult(base, false, List.of("端侧模型未就绪，AI 精判回退规则分"));
        }
        double aiScore = FastRuleLayer.clamp01(ir.score());
        double score = Math.max(base, aiScore);
        List<String> reasons = new ArrayList<>(windowReasons(window));
        for (Map.Entry<String, Double> e : ir.contributions().entrySet()) {
            if (Math.abs(e.getValue()) >= CONTRIBUTION_MIN) {
                reasons.add("特征贡献 " + e.getKey() + "（" + fmt(e.getValue()) + "）");
            }
            if (reasons.size() >= MAX_REASONS) {
                break;
            }
        }
        if (reasons.isEmpty() && aiScore >= SUSPICIOUS_SCORE) {
            reasons.add("AI 精判分超阈值（" + fmt(aiScore) + "）");
        }
        return new AiResult(score, aiScore >= SUSPICIOUS_SCORE, List.copyOf(reasons));
    }

    /** 窗口上下文：信号波动与到达间隔（规则层之外的补充依据）。 */
    private static List<String> windowReasons(SlidingWindow window) {
        if (window == null || window.size() < 2) {
            return List.of();
        }
        List<String> out = new ArrayList<>(2);
        double mean = window.mean();
        double std = window.std();
        if (mean > 0 && std > mean * 0.8) {
            out.add("窗口信号波动偏大（std/mean=" + fmt(std / mean) + "）");
        }
        double meanIntervalMs = window.meanIntervalMs();
        if (meanIntervalMs > 0 && meanIntervalMs < 30.0) {
            out.add("到达间隔过短（" + fmt(meanIntervalMs) + "ms）");
        }
        return out;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}