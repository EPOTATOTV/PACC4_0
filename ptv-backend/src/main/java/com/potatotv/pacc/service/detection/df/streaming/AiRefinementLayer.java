package com.potatotv.pacc.service.detection.df.streaming;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DF §4.1.1 AI 精判层：在快速规则层存疑时才运行的小型 logistic 精判（目标 &lt;10ms）。
 *
 * <p>输入为增量特征快照（在线均值 / 标准差 / EWMA）、滑动窗口速率与节律突变、规则分与严重级；
 * 权重为固定常量（纯 JDK 实现，不引入推理依赖），因此单次调用只做十几次乘加，延迟远低于毫秒级门禁。
 * 采用「规则先筛、AI 后判」的两级结构，可让绝大多数正常事件停在规则层，直接压低游戏中的 CPU 占用。</p>
 */
@Service
public class AiRefinementLayer {

    /** logistic 截距。 */
    private static final double BIAS = -2.2;
    /** 各输入的权重（顺序与 {@link #vector} 一致）。 */
    private static final double[] WEIGHTS = {1.9, 1.2, 1.1, 0.9, 1.6, 1.4, 0.6};
    /** 精判得分达到该值即视为可疑。 */
    public static final double SUSPICIOUS_SCORE = 0.5;
    /** 各权重对应的贡献名称。 */
    private static final String[] NAMES = {
            "信号均值偏高", "信号波动偏大", "近期信号滑升", "事件到达过快", "节律突变", "规则分偏高", "严重级偏高"
    };

    /** 精判结果。 */
    public record AiResult(double score, boolean suspicious, List<String> contributions) {
    }

    /**
     * 精判一条事件。
     *
     * @param event     待精判事件
     * @param extractor 增量特征提取器（全量在线统计）
     * @param window    滑动窗口
     * @param ruleScore 快速规则层得分 0-1
     */
    public AiResult refine(StreamEvent event, IncrementalFeatureExtractor extractor,
                           SlidingWindow window, double ruleScore) {
        double[] x = vector(event, extractor, window, ruleScore);
        double z = BIAS;
        List<String> contributions = new ArrayList<>();
        for (int i = 0; i < WEIGHTS.length; i++) {
            double contribution = WEIGHTS[i] * x[i];
            z += contribution;
            if (contribution >= 0.4) {
                contributions.add(NAMES[i] + "（+" + fmt(contribution) + "）");
            }
        }
        double score = sigmoid(z);
        if (score >= SUSPICIOUS_SCORE && contributions.isEmpty()) {
            contributions.add("综合精判分超阈值（" + fmt(score) + "）");
        }
        return new AiResult(score, score >= SUSPICIOUS_SCORE, List.copyOf(contributions));
    }

    /** 归一化输入向量：所有分量都压到 0-1，避免量纲差异主导 logistic。 */
    private static double[] vector(StreamEvent event, IncrementalFeatureExtractor extractor,
                                   SlidingWindow window, double ruleScore) {
        double mean = extractor == null ? event.signal() : extractor.mean("signal");
        double std = extractor == null ? 0.0 : extractor.std("signal");
        double ewma = extractor == null ? event.signal() : extractor.ewma("signal");
        double rate = window == null ? 0.0 : window.ratePerSecond();
        double burst = window == null ? 0.0 : window.burstScore();
        return new double[]{
                clamp01(mean / 20.0),
                clamp01(std / 10.0),
                clamp01(ewma / 20.0),
                clamp01(rate / 50.0),
                clamp01(burst),
                clamp01(ruleScore),
                FastRuleLayer.severityBonus(event.severity())
        };
    }

    private static double sigmoid(double z) {
        return 1.0 / (1.0 + Math.exp(-z));
    }

    private static double clamp01(double v) {
        return v < 0 ? 0.0 : Math.min(v, 1.0);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}