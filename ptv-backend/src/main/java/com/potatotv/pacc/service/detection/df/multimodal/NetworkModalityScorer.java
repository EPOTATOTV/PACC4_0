package com.potatotv.pacc.service.detection.df.multimodal;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DF §4.1.3 网络模态评分器：本地网络栈采样 → 代理 / VPN / 异常连接。
 *
 * <p>代理与 VPN 标志是硬信号；DNS 异常分、连接扇出与延迟抖动用于兜住无显式标志的隧道与中转链路。</p>
 */
@Component
public class NetworkModalityScorer extends WeightedModalityScorer {

    private static final List<Signal> SIGNALS = List.of(
            new Signal("proxy_flag", 1.0, Direction.HIGH_BAD),
            new Signal("vpn_flag", 1.0, Direction.HIGH_BAD),
            new Signal("dns_anomaly_score", 0.8, Direction.HIGH_BAD),
            new Signal("connection_fanout", 40.0, Direction.HIGH_BAD),
            new Signal("latency_jitter_ms", 80.0, Direction.HIGH_BAD));

    @Override
    public Modality modality() {
        return Modality.NETWORK;
    }

    @Override
    protected List<Signal> signalSpecs() {
        return SIGNALS;
    }
}