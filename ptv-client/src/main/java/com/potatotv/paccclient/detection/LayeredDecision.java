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
}
