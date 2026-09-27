package com.potatotv.paccclient.detection.stream;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DF §4.1.1 快速规则层：单条事件的常数时间阈值判定（目标 &lt;1ms）。
 *
 * <p>规则是「按事件类型的信号上限」+「严重级加权」+「到达节律突变」三者之和：任何输入都只做常数次
 * 比较与算术，不建索引、不做 IO，因此可稳定压在亚毫秒级。它是两级判定结构的前置过滤器——命中
 * 明确阈值或分数极低时直接给出结论（{@link Verdict#FLAGGED} / {@link Verdict#CLEAR}），只有
 * 介于两者之间的存疑事件才进入 AI 精判层（{@link Verdict#INCONCLUSIVE}），从而让绝大多数正常
 * 事件止步于规则层、压低游戏中 CPU 占用。</p>
 */
public final class FastRuleLayer {

    /** 规则层直接判定为命中的分数门限。 */
    public static final double FLAG_SCORE = 0.6;
    /** 规则层存疑（进入 AI 精判）的分数下限。 */
    public static final double INCONCLUSIVE_SCORE = 0.4;

    /** 规则层结论。 */
    public enum Verdict {
        /** 明显正常：规则层直接放行，不触发 AI。 */
        CLEAR,
        /** 明显异常：规则层直接判定，不触发 AI。 */
        FLAGGED,
        /** 存疑：交由 AI 精判层复核。 */
        INCONCLUSIVE
    }

    /** 规则判定结果。 */
    public record RuleResult(Verdict verdict, double score, List<String> reasons) {
    }

    /**
     * 快速规则评估。
     *
     * @param event  待判定事件
     * @param window 该事件的滑动窗口（可为 {@code null}，表示不做节律判定）
     */
    public RuleResult evaluate(StreamEvent event, SlidingWindow window) {
        List<String> reasons = new ArrayList<>(2);
        double score = 0.0;

        Rule rule = ruleFor(event.eventType());
        if (rule != null) {
            double ratio = event.signal() / rule.limit();
            if (ratio >= 1.0) {
                score = 0.5 + 0.5 * Math.min(1.0, ratio - 1.0);
                reasons.add(rule.label() + " 信号超限（" + fmt(event.signal()) + " ≥ " + fmt(rule.limit()) + "）");
            } else if (ratio > 0) {
                score = 0.5 * ratio;
            }
        }

        double burst = window == null ? 0.0 : window.burstScore();
        if (burst >= 0.5) {
            reasons.add("到达节律突变（burst=" + fmt(burst) + "）");
        }
        score = clamp01(score + severityBonus(event.severity()) * 0.1 + burst * 0.3);

        Verdict verdict = score >= FLAG_SCORE ? Verdict.FLAGGED
                : score >= INCONCLUSIVE_SCORE ? Verdict.INCONCLUSIVE : Verdict.CLEAR;
        if (verdict != Verdict.CLEAR && reasons.isEmpty()) {
            reasons.add("综合规则分超阈值（" + fmt(score) + "）");
        }
        return new RuleResult(verdict, score, List.copyOf(reasons));
    }

    /** 严重级加权：critical 最高。 */
    public static double severityBonus(String severity) {
        if (severity == null) {
            return 0.0;
        }
        return switch (severity.toLowerCase(Locale.ROOT)) {
            case "critical" -> 1.0;
            case "high" -> 0.75;
            case "medium" -> 0.5;
            case "low" -> 0.25;
            default -> 0.0;
        };
    }

    static double clamp01(double v) {
        return v < 0 ? 0.0 : Math.min(v, 1.0);
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    /** 按事件类型查信号上限与展示名；未知类型返回 null（只按严重级与节律评分）。 */
    private static Rule ruleFor(String eventType) {
        if (eventType == null) {
            return null;
        }
        return switch (eventType.toLowerCase(Locale.ROOT)) {
            case "auto_clicker", "autoclicker" -> new Rule(14.0, "连点器");
            case "killaura", "aimbot" -> new Rule(45.0, "自瞄/杀戮光环");
            case "reach" -> new Rule(4.0, "超距攻击");
            case "speed" -> new Rule(1.5, "加速");
            case "fly" -> new Rule(1.2, "飞行");
            case "crystal_aura" -> new Rule(0.5, "水晶光环");
            case "memory_tamper" -> new Rule(1.0, "内存篡改");
            case "process_injection" -> new Rule(1.0, "进程注入");
            case "java_mod" -> new Rule(1.0, "Java 模组");
            case "proxy", "vpn" -> new Rule(1.0, "代理/VPN");
            case "script" -> new Rule(0.8, "脚本节律");
            default -> null;
        };
    }

    /** 事件类型 → 信号上限与展示名。 */
    private record Rule(double limit, String label) {
    }
}