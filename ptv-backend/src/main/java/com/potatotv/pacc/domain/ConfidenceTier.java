package com.potatotv.pacc.domain;

/**
 * 检测置信度三级分级（v4.4 面世准备）。
 * <p>将综合风险评分映射为三级置信度，并对各级采取差异化动作：</p>
 * <ul>
 *   <li>HIGH（≥85）：高置信，红屏警告 + 键盘锁定</li>
 *   <li>MEDIUM（70-84）：记录为疑似 + 深度观察/增强采样，不立即红屏</li>
 *   <li>LOW（&lt;70）：弱特征，仅日志记录，用于分析与模型训练</li>
 * </ul>
 * 阈值与 {@code pacc.detection.suspicious-low / redscreen-threshold} 保持一致。
 */
public enum ConfidenceTier {

    LOW, MEDIUM, HIGH;

    /** 纯函数：按阈值边界判定置信度等级（便于确定性单测）。 */
    public static ConfidenceTier of(int risk, int mediumThreshold, int highThreshold) {
        if (risk >= highThreshold) return HIGH;
        if (risk >= mediumThreshold) return MEDIUM;
        return LOW;
    }
}