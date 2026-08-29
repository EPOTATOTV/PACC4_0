package com.potatotv.paccclient.ai;

import com.potatotv.paccclient.detection.DetectionEvent;

/**
 * 端侧轻量 AI：携带离线模型（XGBoost 结构化特征 + LSTM-AE 时序异常），
 * 对原始特征先做端侧初筛，降低传输与误报。
 * <p>说明：真实模型权重由 PTV 灰度下发，此处为推理协议骨架。</p>
 */
public final class LocalAiModel {

    /** 演示：对端侧事件做一次初筛分（0-100）。 */
    public int preScore(DetectionEvent e) {
        int base = Math.max(0, Math.min(100, e.clientRiskScore()));
        // 演示启发：critical 抬升，其余按原值
        if ("critical".equalsIgnoreCase(e.severity())) return Math.min(100, base + 15);
        return base;
    }
}