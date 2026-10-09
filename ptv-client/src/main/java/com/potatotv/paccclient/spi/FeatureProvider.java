package com.potatotv.paccclient.spi;

import java.util.List;
import java.util.Map;

/**
 * 扩展特征提供者（文档 §2.2）：在核心 178 维之外贡献插件自有特征。
 *
 * <p>{@link #prefix()} 是维度命名空间前缀（如 {@code ext_cheatengine_}），用于避免与核心维度和
 * 其它插件冲突；{@link #dimensions()} 声明全部维度；{@link #collect} 每周期采集一次。
 * 采集返回的键必须带 {@code ext_} 前缀，未声明的键会被宿主忽略。</p>
 */
public interface FeatureProvider {

    /** 特征前缀，如 {@code ext_cheatengine_}。 */
    String prefix();

    /** 本提供者贡献的全部维度定义。 */
    List<FeatureDim> dimensions();

    /** 采集一次特征值；键 → 值。实现不应抛出，异常由宿主隔离。 */
    Map<String, Double> collect(FeatureCollectContext ctx);
}