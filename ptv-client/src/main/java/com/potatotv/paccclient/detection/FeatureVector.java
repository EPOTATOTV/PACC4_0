package com.potatotv.paccclient.detection;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.1 行为特征向量（128 维，端侧采集，上报 PTV 做 AI 行为画像）。
 * 维度规划：战斗 52 / 移动 44 / 环境设备 30 / JVM 20 / 模组 15（Java 版）。
 */
public final class FeatureVector {

    private final Map<String, Double> features = new LinkedHashMap<>();

    public FeatureVector put(String key, double value) {
        features.put(key, value);
        return this;
    }

    public double get(String key) {
        return features.getOrDefault(key, 0.0);
    }

    public Map<String, Double> asMap() {
        return features;
    }

    public int size() {
        return features.size();
    }

    /** 序列化为 evidence detailJson（供后端 {@code FeatureVector.fromJson} 还原）。 */
    public String toDetailJson() {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        for (Map.Entry<String, Double> e : features.entrySet()) {
            if (i++ > 0) sb.append(',');
            sb.append('"').append(e.getKey()).append("\":").append(e.getValue());
        }
        return sb.append('}').toString();
    }

    @Override
    public String toString() {
        return features.toString();
    }
}
