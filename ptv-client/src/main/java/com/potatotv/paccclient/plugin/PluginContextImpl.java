package com.potatotv.paccclient.plugin;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.Detector;
import com.potatotv.paccclient.spi.EventListener;
import com.potatotv.paccclient.spi.FeatureDim;
import com.potatotv.paccclient.spi.FeatureProvider;
import com.potatotv.paccclient.spi.PluginConfig;
import com.potatotv.paccclient.spi.PluginContext;
import com.potatotv.paccclient.spi.PluginLogger;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * {@link PluginContext} 的宿主实现：把插件调用路由到对应注册表 / 事件总线，并在注册时施加入参校验。
 *
 * <p>校验口径（宁可拒绝烂输入，也不让坏数据进到核心链路）：</p>
 * <ul>
 *   <li>检测器：id 非空；注册前套 {@link PluginSandbox}；</li>
 *   <li>特征提供者：前缀以 {@code ext_} 开头，且每个维度键都在该前缀下；</li>
 *   <li>规则：交给 {@link RuleRegistrar}，由 PRL 编译器决定合法性。</li>
 * </ul>
 *
 * <p>包级可见：只由 {@link PluginManager} 构造，避免宿主其它代码误用。</p>
 */
final class PluginContextImpl implements PluginContext {

    private final String pluginId;
    private final SystemProbe systemProbe;
    private final PluginConfig config;
    private final PluginLogger logger;
    private final EventBus eventBus;
    private final PluginSandbox sandbox;
    private final Consumer<Detector> detectorSink;
    private final Consumer<FeatureProvider> featureProviderSink;
    private final RuleRegistrar ruleRegistrar;

    PluginContextImpl(String pluginId,
                      SystemProbe systemProbe,
                      PluginConfig config,
                      PluginLogger logger,
                      EventBus eventBus,
                      PluginSandbox sandbox,
                      Consumer<Detector> detectorSink,
                      Consumer<FeatureProvider> featureProviderSink,
                      RuleRegistrar ruleRegistrar) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.systemProbe = Objects.requireNonNull(systemProbe, "systemProbe");
        this.config = Objects.requireNonNull(config, "config");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.sandbox = Objects.requireNonNull(sandbox, "sandbox");
        this.detectorSink = Objects.requireNonNull(detectorSink, "detectorSink");
        this.featureProviderSink = Objects.requireNonNull(featureProviderSink, "featureProviderSink");
        this.ruleRegistrar = Objects.requireNonNull(ruleRegistrar, "ruleRegistrar");
    }

    @Override
    public void registerDetector(Detector detector) {
        if (detector == null || detector.id() == null || detector.id().isBlank()) {
            logger.warn("忽略非法检测器（id 为空）");
            return;
        }
        detectorSink.accept(sandbox.wrap(pluginId, detector));
    }

    @Override
    public void registerFeatureProvider(FeatureProvider provider) {
        if (provider == null || provider.prefix() == null
                || !provider.prefix().startsWith(FeatureDim.EXT_PREFIX)) {
            logger.warn("忽略非法特征提供者（前缀必须以 " + FeatureDim.EXT_PREFIX + " 开头）");
            return;
        }
        if (provider.dimensions() == null || provider.dimensions().isEmpty()) {
            logger.warn("忽略非法特征提供者（未声明维度）");
            return;
        }
        for (FeatureDim dim : provider.dimensions()) {
            if (dim == null || !dim.key().startsWith(provider.prefix())) {
                logger.warn("忽略非法特征提供者（维度 " + (dim == null ? "null" : dim.key())
                        + " 不在前缀 " + provider.prefix() + " 下）");
                return;
            }
        }
        featureProviderSink.accept(provider);
    }

    @Override
    public void registerRule(String cheatType, String prlScript) {
        if (cheatType == null || cheatType.isBlank() || prlScript == null || prlScript.isBlank()) {
            logger.warn("忽略非法规则（类型或脚本为空）");
            return;
        }
        try {
            ruleRegistrar.register(cheatType, prlScript);
        } catch (RuntimeException e) {
            // PRL 编译失败只废掉这一条规则，不能让整个插件加载失败
            logger.warn("规则注册失败 " + cheatType + ": " + e.getMessage());
        }
    }

    @Override
    public void subscribe(String eventType, EventListener listener) {
        eventBus.subscribe(eventType, listener);
    }

    @Override
    public void emit(DetectionEvent event) {
        if (event == null) {
            return;
        }
        eventBus.publish(event.eventType(), event);
    }

    @Override
    public SystemProbe systemProbe() {
        return systemProbe;
    }

    @Override
    public PluginConfig config() {
        return config;
    }

    @Override
    public PluginLogger logger() {
        return logger;
    }
}