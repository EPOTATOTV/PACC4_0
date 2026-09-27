package com.potatotv.paccclient.detection.stream;

import java.util.Map;

/**
 * DF §4.1.1 输入事件流中的单条事件（端侧采集，字段与云端 {@code StreamEvent} 对齐）。
 *
 * <p>{@link #features()} 的键沿用既有特征模型 {@code FeatureSchema} 的 {@code feature_} 维度名，
 * 因此端侧增量统计与端云上报共用同一套特征键，不新造平行特征表。</p>
 *
 * <p>{@link #receivedAtNanos} 由 {@link StreamDetectionPipeline} 在接收入队瞬间写入
 * （{@code System.nanoTime()}），是端到端延迟的计时起点；端侧原始时间戳单独放在
 * {@link #occurredAtNanos}，不参与延迟统计。</p>
 *
 * @param pteid           玩家 PTEID
 * @param eventType       事件类型（auto_clicker / killaura / speed / fly / reach ...）
 * @param severity        严重级（low / medium / high / critical）
 * @param signal          端侧原始检测信号（语义由 eventType 决定）
 * @param features        端侧特征增量（可空）
 * @param occurredAtNanos 端侧事件时间戳（纳秒，可空按 0）
 * @param receivedAtNanos 端侧流水线接收时间戳（纳秒），由管线写入
 */
public record StreamEvent(
        String pteid,
        String eventType,
        String severity,
        double signal,
        Map<String, Double> features,
        long occurredAtNanos,
        long receivedAtNanos
) {

    /** 端侧特征（永不为 null）。 */
    @Override
    public Map<String, Double> features() {
        return features == null ? Map.of() : features;
    }

    /** 以给定接收时间戳派生一条同内容事件（管线入队前调用）。 */
    public StreamEvent stampedAt(long nanos) {
        return new StreamEvent(pteid, eventType, severity, signal, features, occurredAtNanos, nanos);
    }
}