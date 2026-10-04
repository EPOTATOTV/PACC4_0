package com.potatotv.paccclient.spi;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.probe.SystemProbe;

/**
 * 插件宿主上下文（文档 §2.2）：插件与 PACC 交互的唯一入口。
 *
 * <p>插件拿不到宿主内部的类与资源，只能经由本接口做四件事：注册检测能力、读写私有配置、
 * 读只读系统探针、收发检测事件。文件系统 / 网络 / 进程创建等能力刻意不暴露，
 * 由 {@link PluginClassLoader} 在类加载层面进一步封堵（文档 §2.3 权限白名单）。</p>
 */
public interface PluginContext {

    /** 注册一个检测器（进入调度器，按自身周期执行）。 */
    void registerDetector(Detector detector);

    /** 注册一个特征提供者（扩展 178 维之外的特征）。 */
    void registerFeatureProvider(FeatureProvider provider);

    /**
     * 注册一条 PRL 动态规则。
     *
     * @param cheatType 作弊类型 code（必须是宿主已认识的 {@code CheatType}）
     * @param prlScript PRL 脚本源码
     */
    void registerRule(String cheatType, String prlScript);

    /** 订阅检测事件；{@code eventType} 传 {@code *} 表示订阅全部。 */
    void subscribe(String eventType, EventListener listener);

    /** 发出检测事件，进入 PACC 统一事件流。 */
    void emit(DetectionEvent event);

    /** 只读系统探针（进程 / 内存 / 网络 / 文件等，能力按平台降级）。 */
    SystemProbe systemProbe();

    /** 插件私有配置存储。 */
    PluginConfig config();

    /** 带插件前缀的日志。 */
    PluginLogger logger();
}