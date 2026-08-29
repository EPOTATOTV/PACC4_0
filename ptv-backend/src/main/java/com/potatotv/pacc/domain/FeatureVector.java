package com.potatotv.pacc.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.1 行为特征向量（128 维，基岩版与 Java 版统一特征空间）。
 * <p>维度分布：战斗 52 + 移动 44 + 环境/设备 30 + JVM 20 + 模组 15，接入端侧可扩展。</p>
 * <p>序列化为 {@code feature_xxx} 键值，供 AI 行为画像引擎（XGBoost/LSTM）消费。</p>
 */
public final class FeatureVector {

    private final Map<String, Double> features = new LinkedHashMap<>();

    public FeatureVector set(String key, double value) {
        features.put(key, value);
        return this;
    }

    public double get(String key) {
        return features.getOrDefault(key, 0.0);
    }

    public Map<String, Double> asMap() {
        return Map.copyOf(features);
    }

    public int size() {
        return features.size();
    }

    public boolean isEmpty() {
        return features.isEmpty();
    }

    /** 便捷构造：从检测事件证据中的 JSON 特征还原。 */
    public static FeatureVector fromJson(String detailJson) {
        FeatureVector v = new FeatureVector();
        if (detailJson == null || detailJson.isBlank()) return v;
        // 简化解析：仅解析 {"feature_k": 12.3, ...} 形式
        String body = detailJson.trim();
        if (body.startsWith("{")) body = body.substring(1);
        if (body.endsWith("}")) body = body.substring(0, body.length() - 1);
        for (String pair : body.split(",")) {
            String[] kv = pair.split(":");
            if (kv.length != 2) continue;
            String k = kv[0].trim().replace("\"", "");
            if (!k.startsWith("feature_")) continue;
            try {
                v.features.put(k, Double.parseDouble(kv[1].trim()));
            } catch (NumberFormatException ignored) {
                // 跳过非数值特征
            }
        }
        return v;
    }

    @Override
    public String toString() {
        return features.toString();
    }
}