package com.potatotv.pacc.domain.plugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 检测插件输出结果：沙箱只接受该类型，异常统一隔离为 {@link #failure(String)}。
 */
public final class DetectionResult {

    private final boolean detected;
    private final String cheatType;
    private final double confidence;
    private final int riskScore;
    private final Map<String, Object> evidence;
    private final String error;

    private DetectionResult(boolean detected, String cheatType, double confidence, int riskScore,
                            Map<String, Object> evidence, String error) {
        this.detected = detected;
        this.cheatType = cheatType;
        this.confidence = confidence;
        this.riskScore = riskScore;
        this.evidence = evidence;
        this.error = error;
    }

    /** 未命中：正常返回，无风险。 */
    public static DetectionResult clean() {
        return new DetectionResult(false, null, 0.0, 0, Map.of(), null);
    }

    /** 命中：给出作弊类型与置信度。 */
    public static DetectionResult hit(String cheatType, double confidence, int riskScore,
                                      Map<String, Object> evidence) {
        return new DetectionResult(true, cheatType, clamp(confidence), clampScore(riskScore),
                evidence == null ? Map.of() : new LinkedHashMap<>(evidence), null);
    }

    /** 沙箱隔离后的失败占位结果（绝不向上抛异常，避免阻断检测流水线）。 */
    public static DetectionResult failure(String error) {
        return new DetectionResult(false, null, 0.0, 0, Map.of(), error);
    }

    public boolean isDetected() {
        return detected;
    }

    public String getCheatType() {
        return cheatType;
    }

    public double getConfidence() {
        return confidence;
    }

    public int getRiskScore() {
        return riskScore;
    }

    public Map<String, Object> getEvidence() {
        return evidence;
    }

    /** 失败原因；正常返回时为 {@code null}。 */
    public String getError() {
        return error;
    }

    public boolean isFailure() {
        return error != null;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private static int clampScore(int v) {
        return Math.max(0, Math.min(100, v));
    }
}