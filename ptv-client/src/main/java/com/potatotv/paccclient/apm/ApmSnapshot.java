package com.potatotv.paccclient.apm;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * APM 单次采样切片：一个时间点上的全部指标集合。
 *
 * <p>为什么用可变 builder 而不是不可变对象：采样器每秒组装一次快照，四个采集器依次往里塞指标，
 * 用 {@link #with(String, double)} 串联可以避免为每个维度创建中间对象；快照一旦进入环形缓冲
 * 就不再被写入，读取方只按约定不修改即可。</p>
 *
 * <p>指标容器使用 {@link LinkedHashMap}：单 tick 一次分配可接受（1 次/秒），保持插入顺序便于排障时
 * 阅读上报报文。</p>
 */
public final class ApmSnapshot {

    private final long timestamp;
    private final String platform;
    private final Map<String, Double> metrics = new LinkedHashMap<>();

    public ApmSnapshot(long timestamp, String platform) {
        this.timestamp = timestamp;
        this.platform = platform == null ? "" : platform;
    }

    /** 采样时刻（epoch 毫秒），对应上报契约里的 {@code metric_time}。 */
    public long timestamp() {
        return timestamp;
    }

    /** 平台标识（WIN / LINUX / MAC），对应上报契约里的 {@code platform}。 */
    public String platform() {
        return platform;
    }

    /** 写入/覆盖一个指标并返回自身，支持链式组装。 */
    public ApmSnapshot with(String name, double value) {
        if (name != null) {
            metrics.put(name, value);
        }
        return this;
    }

    /** 当前切片已采集到的指标（指标名 → 数值）。 */
    public Map<String, Double> metrics() {
        return metrics;
    }
}