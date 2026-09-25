package com.potatotv.paccclient.detection;

import com.potatotv.paccclient.Json;

import java.util.Arrays;

/**
 * 端侧特征上报（文档 §2.3.3）：把 PCA 降维后的特征 + 端侧预评分 + 触发原因上报 PTV 做云端精判。
 *
 * <p><b>与文档的差异（有意为之）</b>：文档给出的是 {@code PaccWire.proto} 中的 {@code FeatureReport}
 * 消息，但本仓库的 {@code com.potatotv.pacc.proto.PaccWire} 是手工维护的提交产物、CI 中没有
 * protoc 代码生成步骤。为避免改动该文件（及其生成约定），本类以 JSON 文本承载同一份语义，
 * 字段名与 proto 草案保持一致（{@code pteid}/{@code session_id}/{@code timestamp}/
 * {@code features}/{@code local_risk_score}/{@code model_version}/{@code reason}），
 * 后续若接入 protoc 可字段级平移，不需要重新设计。</p>
 *
 * @param pteid             玩家标识
 * @param sessionId         会话标识
 * @param timestampMillis   采样时间戳（毫秒）
 * @param projectedFeatures PCA 降维后的特征（文档 §2.2.1：178 → 64 维）
 * @param localRiskScore    端侧预评分（0-100）
 * @param modelVersion      端侧模型版本（无模型为空串）
 * @param reason            上报触发原因
 */
public record FeatureReport(String pteid, String sessionId, long timestampMillis,
                            double[] projectedFeatures, int localRiskScore,
                            String modelVersion, TriggerReason reason) {

    /** 上报触发原因（文档 §2.3.3 枚举）。 */
    public enum TriggerReason {
        /** L0 规则命中。 */
        RULE_HIT,
        /** L1 端侧 AI 低置信（0.5-0.85），需云端精判。 */
        AI_LOW_CONFIDENCE,
        /** 周期性心跳上报。 */
        PERIODIC,
        /** 零日可疑（端侧无匹配规则但有异常信号）。 */
        ZERO_DAY_SUSPECT
    }

    public FeatureReport {
        projectedFeatures = projectedFeatures == null ? new double[0] : projectedFeatures.clone();
        modelVersion = modelVersion == null ? "" : modelVersion;
    }

    /** 上报的特征维度数（文档期望 64）。 */
    public int featureCount() {
        return projectedFeatures.length;
    }

    @Override
    public double[] projectedFeatures() {
        return projectedFeatures.clone();
    }

    /** 序列化为上报 JSON 文本（负零归一为 0，避免无关字节差异）。 */
    public String toJson() {
        StringBuilder sb = new StringBuilder(64 + projectedFeatures.length * 12);
        sb.append('{')
                .append("\"pteid\":").append(Json.encode(pteid)).append(',')
                .append("\"session_id\":").append(Json.encode(sessionId)).append(',')
                .append("\"timestamp\":").append(timestampMillis).append(',')
                .append("\"local_risk_score\":").append(Math.max(0, Math.min(100, localRiskScore))).append(',')
                .append("\"model_version\":").append(Json.encode(modelVersion)).append(',')
                .append("\"reason\":").append(Json.encode(reason == null ? TriggerReason.PERIODIC.name() : reason.name()))
                .append(",\"features\":[");
        for (int i = 0; i < projectedFeatures.length; i++) {
            if (i > 0) sb.append(',');
            double v = projectedFeatures[i];
            sb.append(v == 0.0 ? 0.0 : v);
        }
        return sb.append("]}").toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FeatureReport other)) return false;
        return timestampMillis == other.timestampMillis
                && localRiskScore == other.localRiskScore
                && Arrays.equals(projectedFeatures, other.projectedFeatures)
                && pteid.equals(other.pteid)
                && sessionId.equals(other.sessionId)
                && modelVersion.equals(other.modelVersion)
                && reason == other.reason;
    }

    @Override
    public int hashCode() {
        int h = pteid.hashCode();
        h = 31 * h + sessionId.hashCode();
        h = 31 * h + Long.hashCode(timestampMillis);
        h = 31 * h + Arrays.hashCode(projectedFeatures);
        h = 31 * h + localRiskScore;
        h = 31 * h + modelVersion.hashCode();
        h = 31 * h + (reason == null ? 0 : reason.hashCode());
        return h;
    }

    @Override
    public String toString() {
        return "FeatureReport[pteid=" + pteid + ", session=" + sessionId + ", dim=" + projectedFeatures.length
                + ", score=" + localRiskScore + ", reason=" + reason + "]";
    }
}
