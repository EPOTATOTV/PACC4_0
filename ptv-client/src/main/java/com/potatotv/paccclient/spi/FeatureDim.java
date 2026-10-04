package com.potatotv.paccclient.spi;

import java.util.Objects;

/**
 * 插件扩展特征维度定义（文档 §2.2 {@code FeatureProvider.dimensions}）。
 *
 * <p>键必须以 {@code ext_} 开头：核心 178 维由端侧 AI / 降维按固定顺序消费，
 * 插件不得占用或覆盖；宿主在注册时会拒绝不合规的键（见 {@code PluginContextImpl}）。</p>
 *
 * @param key         特征键，必须带 {@code ext_} 前缀
 * @param description 人类可读说明
 * @param min         取值下界（文档用，用于归一化/展示）
 * @param max         取值上界
 */
public record FeatureDim(String key, String description, double min, double max) {

    /** 扩展特征保留前缀。 */
    public static final String EXT_PREFIX = "ext_";

    public FeatureDim {
        Objects.requireNonNull(key, "key");
        if (!key.startsWith(EXT_PREFIX) || key.length() == EXT_PREFIX.length()) {
            throw new IllegalArgumentException("扩展特征键必须以 " + EXT_PREFIX + " 开头：" + key);
        }
        if (max < min) throw new IllegalArgumentException("max 不能小于 min：" + key);
    }
}