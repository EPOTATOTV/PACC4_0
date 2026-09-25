package com.potatotv.pacc.service.detection.df.multimodal;

import java.util.Map;

/**
 * DF §4.1.3 单模态输入：该模态上报的特征快照（键值对，数值型）。
 *
 * <p>键名同时接受裸名与 {@code feature_} 前缀两种写法（{@code click_cps} 与
 * {@code feature_click_cps} 等价），避免与既有 128 维特征空间命名冲突。</p>
 *
 * @param features 特征键值对；空表示该模态本次没有数据（缺失模态）
 */
public record ModalityInput(Map<String, Double> features) {

    /** 规范化：永不持有 null，且只保留非 null 数值。 */
    public ModalityInput {
        if (features == null || features.isEmpty()) {
            features = Map.of();
        } else {
            var copy = new java.util.LinkedHashMap<String, Double>();
            for (Map.Entry<String, Double> e : features.entrySet()) {
                if (e.getKey() != null && e.getValue() != null) {
                    copy.put(e.getKey(), e.getValue());
                }
            }
            features = Map.copyOf(copy);
        }
    }

    /** 是否存在该信号（裸名或 feature_ 前缀任一存在即算存在）。 */
    public boolean has(String name) {
        return find(name) != null;
    }

    /** 取值；缺失返回 0。 */
    public double get(String name) {
        Double v = find(name);
        return v == null ? 0.0 : v;
    }

    /** 查找原始值；缺失返回 null。 */
    public Double find(String name) {
        Double v = features.get(name);
        if (v != null) {
            return v;
        }
        return features.get("feature_" + name);
    }

    /** 该模态是否没有任何数据。 */
    public boolean absent() {
        return features.isEmpty();
    }

    /** 给定信号集合中实际出现的信号数（用于覆盖率 / 置信度）。 */
    public int presentCount(Iterable<String> signals) {
        int n = 0;
        for (String s : signals) {
            if (has(s)) {
                n++;
            }
        }
        return n;
    }
}