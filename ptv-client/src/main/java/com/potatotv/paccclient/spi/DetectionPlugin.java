package com.potatotv.paccclient.spi;

/**
 * 检测插件根接口（文档 §2.2）。第三方 JAR 的入口类必须实现本接口。
 *
 * <p>生命周期：加载器实例化入口类 → 调用 {@link #onLoad(PluginContext)} → 期间用上下文注册
 * 检测器 / 特征提供者 / 规则 → 卸载时调用 {@link #onUnload()}。两个回调都不应抛出：
 * 宿主会捕获异常并隔离，但抛出意味着本插件后续不再被信任。</p>
 */
public interface DetectionPlugin {

    /** 插件元信息，必须与 {@code META-INF/pacc-plugin.json} 一致。 */
    PluginMetadata metadata();

    /** 初始化：注册检测器 / 特征提供者 / 规则，读取配置。 */
    void onLoad(PluginContext context);

    /** 卸载：释放资源。默认空实现，便于只注册周期检测器的简单插件。 */
    default void onUnload() {
    }
}