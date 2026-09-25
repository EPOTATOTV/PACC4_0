package com.potatotv.paccclient.detection;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v5.2 行为特征向量（178 维，端侧采集，上报 PTV 做 AI 行为画像）。
 * 维度定义见 {@link FeatureSchema}：战斗 52 / 移动 44 / 环境设备 30 / JVM 20 / 模组 15 / 网络 17。
 *
 * <p>容器保持插入顺序的 {@link LinkedHashMap}，{@link #toDetailJson()} 的输出格式对后端
 * {@code FeatureVector.fromJson} 保持向后兼容（{@code {"feature_k":1.0,...}}）。</p>
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

    /** 按 {@link FeatureSchema} 顺序取值；未设置的维度记 0，便于模型/降维统一入参。 */
    public double[] toArray() {
        return toArray(FeatureSchema.keys());
    }

    /** 按给定键序取值。 */
    public double[] toArray(List<String> keys) {
        double[] out = new double[keys.size()];
        for (int i = 0; i < keys.size(); i++) out[i] = get(keys.get(i));
        return out;
    }

    /** 全量 schema 键序（178 维）。 */
    public List<String> keys() {
        return FeatureSchema.keys();
    }

    /** 批量写入；{@code null} 值按 0 处理，便于直接合并各遥测快照。 */
    public FeatureVector putAll(Map<String, Double> values) {
        if (values != null) {
            for (Map.Entry<String, Double> e : values.entrySet()) {
                put(e.getKey(), e.getValue() == null ? 0.0 : e.getValue());
            }
        }
        return this;
    }

    /**
     * 有效维度数：非零、且不等于该维度的中性默认值（如速度倍率默认 1.0）。
     * 用于评估特征向量的真实信息量（文档验收 A02）。
     */
    public int nonPlaceholderCount() {
        int n = 0;
        for (Map.Entry<String, Double> e : features.entrySet()) {
            double v = e.getValue();
            if (v != 0.0 && v != FeatureSchema.NEUTRAL_DEFAULTS.getOrDefault(e.getKey(), 0.0)) n++;
        }
        return n;
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