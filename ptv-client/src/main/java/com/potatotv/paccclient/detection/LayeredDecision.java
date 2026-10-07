package com.potatotv.paccclient.detection;

/**
 * 端云分层判定（文档 §2.3.2 / §2.3.1）。
 *
 * <pre>
 * L0 规则命中            → 直接红屏（高置信）
 * L1 端侧 AI &gt; 0.85      → 红屏（高置信）
 * L1 端侧 AI 0.5 ~ 0.85  → 上报特征到 PTV 做云端精判
 * 其余（&lt; 0.5）          → 周期性上报
 * 零日可疑                → 进入主动学习复核队列
 * </pre>
 *
 * <p>阈值与文档一致；本类只做纯函数映射，不做任何 IO。</p>
 */
public final class LayeredDecision {

    /** L1 端侧 AI 直接红屏阈值（文档 §2.3.2）。 */
    public static final double LOCAL_REDSCREEN_THRESHOLD = 0.85;
    /** 上报云端精判的下限（文档 §2.3.2）。 */
    public static final double CLOUD_REPORT_THRESHOLD = 0.5;

    // ---- 三层融合权重与阈值（文档 §5.3） ----

    /** 网络层证据权重（行为数据最直接）。 */
    public static final double WEIGHT_NETWORK = 0.35;
    /** 屏幕层证据权重（视觉验证辅助）。 */
    public static final double WEIGHT_VISION = 0.25;
    /** 系统层证据权重（注入 / 内存 / 内核最可靠）。 */
    public static final double WEIGHT_SYSTEM = 0.40;
    /** 红屏阈值：融合风险分达到该值端侧直接处置。 */
    public static final double RISK_REDSCREEN = 85.0;
    /** 上报阈值：达到该值上报云端精判。 */
    public static final double RISK_REPORT = 50.0;
    /** 记录阈值：达到该值至少留痕 / 周期性上报。 */
    public static final double RISK_RECORD = 20.0;

    /** 判定结论。 */
    public enum Decision {
        /** L0/L1 高置信：端侧直接处置（红屏/阻断）。 */
        LOCAL_BLOCK,
        /** 中置信：上报云端精判。 */
        REPORT_CLOUD,
        /** 低置信：仅周期性上报。 */
        PERIODIC,
        /** 零日可疑：进入 L3 人工复核队列。 */
        REVIEW_QUEUE
    }

    private LayeredDecision() {
    }

    /**
     * 判定处置层级。
     *
     * @param ruleHit      L0 规则是否命中
     * @param aiConfidence L1 端侧 AI 置信度（0-1）；无模型回退时应传 0
     */
    public static Decision decide(boolean ruleHit, double aiConfidence) {
        if (ruleHit) return Decision.LOCAL_BLOCK;
        if (aiConfidence > LOCAL_REDSCREEN_THRESHOLD) return Decision.LOCAL_BLOCK;
        if (aiConfidence >= CLOUD_REPORT_THRESHOLD) return Decision.REPORT_CLOUD;
        return Decision.PERIODIC;
    }

    /**
     * 带触发原因的判定：{@link FeatureReport.TriggerReason#ZERO_DAY_SUSPECT} 直接进复核队列，
     * 其余按 {@link #decide(boolean, double)} 映射。
     */
    public static Decision decide(FeatureReport.TriggerReason reason, boolean ruleHit, double aiConfidence) {
        if (reason == FeatureReport.TriggerReason.ZERO_DAY_SUSPECT) return Decision.REVIEW_QUEUE;
        return decide(ruleHit, aiConfidence);
    }

    /**
     * 依判定结果推导上报触发原因（{@link Decision#LOCAL_BLOCK} 时无需上报，
     * 返回 {@link FeatureReport.TriggerReason#RULE_HIT} 仅作占位）。
     */
    public static FeatureReport.TriggerReason triggerReason(boolean ruleHit, double aiConfidence) {
        if (ruleHit || aiConfidence > LOCAL_REDSCREEN_THRESHOLD) return FeatureReport.TriggerReason.RULE_HIT;
        if (aiConfidence >= CLOUD_REPORT_THRESHOLD) return FeatureReport.TriggerReason.AI_LOW_CONFIDENCE;
        return FeatureReport.TriggerReason.PERIODIC;
    }

    // ------------------------------------------------------------------ 三层融合（文档 §5.3）

    /**
     * 三层证据加权融合（文档 §5.3）：网络 35% / 屏幕 25% / 系统 40%。
     *
     * <p>入参是各层综合分（0-100），越界值先夹到区间内再按权重求和，返回 0-100 的融合风险分。
     * 未启用的层传 0 即视为无证据，不会因为权重和为 1 而被「稀释」——这与文档
     * §7 注意事项 7「每层独立开关，关闭后不影响其他层」一致：关掉屏幕层只是拿不到这 25% 的
     * 加成，网络层 80 分依旧是 80×0.35=28 的融合贡献，而不是被重新归一化成满分。</p>
     *
     * @param networkScore 网络层综合分（0-100）
     * @param visionScore  屏幕层综合分（0-100）
     * @param systemScore  系统层综合分（0-100）
     * @return 融合风险分（0-100）
     */
    public static double fusedRisk(double networkScore, double visionScore, double systemScore) {
        double fused = clamp100(networkScore) * WEIGHT_NETWORK
                + clamp100(visionScore) * WEIGHT_VISION
                + clamp100(systemScore) * WEIGHT_SYSTEM;
        return clamp100(fused);
    }

    /**
     * 依融合风险分映射处置层级（文档 §5.3）：
     * {@code ≥85} 红屏 / {@code ≥50} 上报云端 / {@code ≥20} 记录留痕；其余周期上报。
     */
    public static Decision decideByRisk(double risk) {
        if (risk >= RISK_REDSCREEN) return Decision.LOCAL_BLOCK;
        if (risk >= RISK_REPORT) return Decision.REPORT_CLOUD;
        return Decision.PERIODIC;
    }

    /**
     * 融合风险分的上报触发原因；记录级（≥20 但 &lt;50）归入周期性上报。
     */
    public static FeatureReport.TriggerReason riskTriggerReason(double risk) {
        if (risk >= RISK_REPORT) return FeatureReport.TriggerReason.AI_LOW_CONFIDENCE;
        return FeatureReport.TriggerReason.PERIODIC;
    }

    private static double clamp100(double value) {
        if (!Double.isFinite(value) || value <= 0) return 0.0;
        return Math.min(100.0, value);
    }
}
