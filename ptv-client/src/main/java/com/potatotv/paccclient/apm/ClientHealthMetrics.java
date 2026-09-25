package com.potatotv.paccclient.apm;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端健康维度指标采集：运行时长、配置指纹、完整性/反调试/反注入状态、链路与上报质量。
 *
 * <p>这些状态由客户端各处事件驱动（WSS 连上、重连、上报成败、红屏、崩溃、安全评估结论），
 * 因此提供公开可变 sink；{@link #SINK} 是全局唯一实例，{@link ApmCollector} 直接采样它，
 * 避免把健康状态在多个对象间复制导致口径不一致。</p>
 *
 * <p>关于 {@code client_config_hash}：APM 通道的取值必须是有限数值，无法承载 16 位十六进制字符串，
 * 因此这里上报的是哈希前 8 位十六进制折算成的整数投影（稳定、可比、可做变更告警）；完整哈希字符串
 * 随远程证明载荷（{@code config_hash}）上报，两者不会混淆。</p>
 */
public final class ClientHealthMetrics {

    /** 全局唯一健康状态实例。 */
    public static final ClientHealthMetrics SINK = new ClientHealthMetrics();

    private final long startMillis = System.currentTimeMillis();
    private final AtomicLong wssReconnectCount = new AtomicLong();
    private final AtomicLong reportSuccess = new AtomicLong();
    private final AtomicLong reportFailure = new AtomicLong();
    private final AtomicLong reportQueueSize = new AtomicLong();
    private final AtomicLong redscreenCount = new AtomicLong();
    private final AtomicLong crashCount = new AtomicLong();
    private final AtomicInteger wssConnected = new AtomicInteger();
    private final AtomicInteger integrityState = new AtomicInteger();
    private final AtomicInteger antidebugState = new AtomicInteger();
    private final AtomicInteger antihookState = new AtomicInteger();
    private volatile String configHash = "";

    public ClientHealthMetrics() {
    }

    /** WSS 链路连接状态变化。 */
    public void setWssConnected(boolean connected) {
        wssConnected.set(connected ? 1 : 0);
    }

    /** 发生一次 WSS 重连（单调计数器）。 */
    public void onWssReconnect() {
        wssReconnectCount.incrementAndGet();
    }

    /** 记录一次上报结果（APM/WSS 等批量上报），用于成功率。 */
    public void onReport(boolean success) {
        if (success) reportSuccess.incrementAndGet();
        else reportFailure.incrementAndGet();
    }

    /** 当前待上报积压条数（本地缓冲/离线队列）。 */
    public void setReportQueueSize(int size) {
        reportQueueSize.set(Math.max(0, size));
    }

    /** 触发一次红屏（单调计数器）。 */
    public void onRedscreen() {
        redscreenCount.incrementAndGet();
    }

    /** 记录一次崩溃（未捕获异常 / 异常退出，单调计数器）。 */
    public void onCrash() {
        crashCount.incrementAndGet();
    }

    /** 代码完整性状态：0 正常，非 0 异常。 */
    public void setIntegrityState(int state) {
        integrityState.set(state);
    }

    /** 反调试评估状态：0 正常，非 0 命中。 */
    public void setAntidebugState(int state) {
        antidebugState.set(state);
    }

    /** 反注入/反 hook 评估状态：0 正常，非 0 命中。 */
    public void setAntihookState(int state) {
        antihookState.set(state);
    }

    /** 设置客户端配置指纹（SHA-256 十六进制前 16 位）。 */
    public void setConfigHash(String hash) {
        this.configHash = hash == null ? "" : hash;
    }

    /** 当前配置指纹（供远程证明使用）。 */
    public String configHash() {
        return configHash;
    }

    /** 组装健康维度指标。单个字段读取失败不影响其他字段。 */
    public void collect(ApmSnapshot snapshot) {
        try {
            snapshot.with("client_uptime", (System.currentTimeMillis() - startMillis) / 1000.0);
            double configMetric = configHashMetric();
            if (configMetric >= 0) snapshot.with("client_config_hash", configMetric);
            snapshot.with("client_integrity_state", integrityState.get());
            snapshot.with("client_antidebug_state", antidebugState.get());
            snapshot.with("client_antihook_state", antihookState.get());
            snapshot.with("client_wss_connected", wssConnected.get());
            snapshot.with("client_wss_reconnect_count", wssReconnectCount.get());

            long success = reportSuccess.get();
            long failure = reportFailure.get();
            long total = success + failure;
            snapshot.with("client_report_success_rate", total <= 0 ? 1.0 : success / (double) total);
            snapshot.with("client_report_queue_size", reportQueueSize.get());
            snapshot.with("client_redscreen_count", redscreenCount.get());
            snapshot.with("client_crash_count", crashCount.get());
        } catch (RuntimeException e) {
            // 忽略：健康维度缺失
        }
    }

    /** 配置指纹的数值投影；未设置或格式异常返回 -1（调用方跳过该指标，不伪造 0）。 */
    private double configHashMetric() {
        String hash = configHash;
        if (hash.length() < 8) return -1;
        try {
            return Long.parseLong(hash.substring(0, 8), 16);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}