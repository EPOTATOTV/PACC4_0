package com.potatotv.pacc.service.detection.df.streaming;

import java.util.Map;

/**
 * DF §4.1.1 输入事件流中的单条事件（端侧采集，云端只做流式判定）。
 *
 * <p>{@link #receivedAtNanos} 由服务端在接收瞬间写入（{@code System.nanoTime()}），是端到端延迟的计时起点；
 * 端侧时间戳单独放在 {@link #occurredAtNanos}，不参与服务端延迟统计（避免时钟漂移与网络往返污染指标）。</p>
 *
 * @param pteid           玩家 PTEID
 * @param eventType       事件类型（auto_clicker / killaura / speed / fly / reach / memory_tamper ...）
 * @param severity        严重级（low / medium / high / critical）
 * @param signal          端侧原始检测信号（语义由 eventType 决定，如 CPS、角度速度、速度倍数）
 * @param features        端侧已抽取的特征增量（可空）
 * @param occurredAtNanos 端侧事件时间戳（纳秒，可空按 0）
 * @param receivedAtNanos 服务端接收时间戳（纳秒），由引擎写入
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
    public Map<String, Double> features() {
        return features == null ? Map.of() : features;
    }

    /** 以给定接收时间戳派生一条同内容事件（引擎入队前调用）。 */
    public StreamEvent stampedAt(long nanos) {
        return new StreamEvent(pteid, eventType, severity, signal, features, occurredAtNanos, nanos);
    }
}