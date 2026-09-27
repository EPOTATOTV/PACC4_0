package com.potatotv.pacc.domain.plugin;

import com.potatotv.pacc.domain.FeatureVector;

/**
 * §4.2.2 检测插件 SPI：第三方开发者实现该接口即可开发检测规则插件，由
 * {@code PluginManager} 热加载 / 卸载，{@code PluginSandbox} 隔离执行。
 *
 * <p>生命周期：{@link #initialize()} → 多次 {@link #detect(FeatureVector)} → {@link #destroy()}。
 * 实现须无状态或自行保证线程安全；一切方法抛出的异常都会被沙箱隔离。</p>
 */
public interface DetectionPlugin {

    /** 初始化：加载规则/模型/索引等一次性资源。 */
    void initialize();

    /** 对单个特征向量执行检测。 */
    DetectionResult detect(FeatureVector vector);

    /** 插件元数据（含声明的沙箱 API 白名单令牌）。 */
    PluginMetadata getMetadata();

    /** 释放资源，卸载时调用。 */
    void destroy();

    /**
     * 带受限上下文的检测重载（默认回退到单参版本）。
     * <p>需要玩家标识 / 版本 / 租户维度的插件可覆写此方法。</p>
     */
    default DetectionResult detect(FeatureVector vector, DetectionContext context) {
        return detect(vector);
    }
}