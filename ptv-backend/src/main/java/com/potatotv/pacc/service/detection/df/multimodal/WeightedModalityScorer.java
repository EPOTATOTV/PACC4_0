package com.potatotv.pacc.service.detection.df.multimodal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DF §4.1.3 加权模态评分器基类：把「期望信号 → 阈值 + 方向」的声明式配置展开为统一的评分流程。
 *
 * <p>每个期望信号按方向归一化到 0-1 子分（越大越可疑 / 越小越可疑），取出现信号子分的均值作为基础分，
 * 再对「多个信号同时偏高」的相互印证给出小幅加成——单信号异常可能是噪声，多信号共发才更可信。</p>
 *
 * <p>缺失信号不参与均值（而不是记 0 分），避免「没上报数据」被误判为「正常」；覆盖率越低，
 * {@link ModalityScore#confidence()} 越低，融合侧能据此降低该模态影响。</p>
 */
abstract class WeightedModalityScorer implements ModalityScorer {

    /** 信号方向：值越大越可疑 / 值越小越可疑。 */
    enum Direction { HIGH_BAD, LOW_BAD }

    /** 期望信号：名称、阈值、方向。 */
    record Signal(String name, double limit, Direction direction) {
    }

    /** 该模态的期望信号。 */
    protected abstract List<Signal> signalSpecs();

    @Override
    public List<String> signals() {
        List<String> out = new ArrayList<>();
        for (Signal s : signalSpecs()) {
            out.add(s.name());
        }
        return List.copyOf(out);
    }

    @Override
    public ModalityScore score(ModalityInput input) {
        List<String> evidence = new ArrayList<>();
        double sum = 0;
        int observed = 0;
        int high = 0;
        for (Signal s : signalSpecs()) {
            if (!input.has(s.name())) {
                continue;
            }
            double value = input.get(s.name());
            double sub = subScore(value, s);
            sum += sub;
            observed++;
            if (sub >= 0.6) {
                high++;
                evidence.add(String.format(Locale.ROOT, "%s=%s 命中（阈值 %s）",
                        s.name(), fmt(value), fmt(s.limit())));
            }
        }
        double base = observed == 0 ? 0.0 : sum / observed;
        double score = clamp01(base + 0.15 * Math.max(0, high - 1));
        if (observed == 0) {
            evidence.add("未识别到有效信号（该模态本次未提供可判定特征）");
        }
        return new ModalityScore(modality(), true, score, confidence(input), List.copyOf(evidence));
    }

    /** 单信号归一化子分。 */
    private static double subScore(double value, Signal s) {
        if (s.limit() <= 0) {
            return 0.0;
        }
        if (s.direction() == Direction.HIGH_BAD) {
            return Math.min(1.0, Math.max(0.0, value / s.limit()));
        }
        // LOW_BAD：越小越可疑；0 视为「未上报」，不贡献
        if (value <= 0) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, (s.limit() - value) / s.limit()));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }
}