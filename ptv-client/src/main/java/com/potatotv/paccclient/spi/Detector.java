package com.potatotv.paccclient.spi;

import com.potatotv.paccclient.detection.DetectionEvent;

import java.util.Optional;

/**
 * 检测器接口（文档 §2.2）：核心专项检测器与第三方插件检测器共用同一契约。
 *
 * <p>每个检测器有自己的执行周期，由 {@code ScannerRunner} 调度：{@link #detect(DetectContext)}
 * 返回空表示本周期未命中，返回事件表示命中。实现必须遵守两条约定：</p>
 * <ul>
 *   <li><b>不抛异常</b>：探针不可用 / 平台不支持时返回空，由调度器兜底隔离；</li>
 *   <li><b>写扩展特征</b>：命中分数写回 {@link DetectContext#putExtended}，供 PRL 规则读取
 *       （文档 §6.2「规则从 ext_ 前缀的扩展特征读取检测器产出的分数」）。</li>
 * </ul>
 *
 * <p>多指标加权原则（文档 §9 注意事项 1）：单个检测器内部也要按多指标加权达到阈值才返回事件，
 * 避免单一线索（如仅进程名相似）直接触发处置。</p>
 */
public interface Detector {

    /** 检测器 ID（全局唯一，用于调度 / 熔断 / 日志）。 */
    String id();

    /**
     * 执行周期（ms）。{@code 0} 表示事件驱动不轮询（由外部显式调用，不参与定时调度）。
     */
    long intervalMs();

    /** 执行一次检测，返回命中事件（空表示未命中）。 */
    Optional<DetectionEvent> detect(DetectContext ctx);

    /** 熔断后恢复调用（可选，默认空实现）。 */
    default void onRecover() {
    }
}