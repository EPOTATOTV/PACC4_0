package com.potatotv.paccclient.detection.network;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.PerfToggles;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 网络行为检测（网络代理层 §2.7）：从本地代理 / 中继旁路解析的行为流打一个综合分。
 *
 * <p>默认关闭（{@link PerfToggles#NET_PROXY}）：关闭时直接返回空，<b>不启动代理</b>。
 * 开启时先 {@link PaccProxy#ensureStarted()}，再从 {@link BehaviorStreamExtractor} 取全部时域指标，
 * 写入 {@code ext_net_}* 扩展维度（13 维），供 PRL 规则与融合层读取。没有任何包入流时不写特征、
 * 直接返回空，避免无数据也产分。多指标求和达 50 才产出 {@code net_behavior_anomaly} 事件，
 * ≥75 记 high。<b>只读取本机旁路字节，不接入游戏服务器、不封禁玩家。</b></p>
 */
public final class NetworkBehaviorScanner implements Detector, AutoCloseable {

    private static final String ID = "network_behavior_scanner";
    private static final long INTERVAL_MS = 5_000L;
    private static final int THRESHOLD = 50;
    private static final int HIGH_THRESHOLD = 75;
    /** 基准水平速度（格/s），用于换速度倍率。 */
    private static final double BASE_SPEED = 5.6;

    private final PaccProxy proxy;

    public NetworkBehaviorScanner() {
        this(new PaccProxy());
    }

    public NetworkBehaviorScanner(PaccProxy proxy) {
        this.proxy = proxy;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        if (!PerfToggles.enabled(PerfToggles.NET_PROXY)) {
            return Optional.empty();
        }
        proxy.ensureStarted();

        BehaviorStreamExtractor ex = proxy.extractor();
        if (ex.packetCount() == 0) {
            return Optional.empty();
        }

        double speed = ex.currentSpeed();
        double speedRatio = speed <= 0 ? 1.0 : speed / BASE_SPEED;
        double verticalSpeed = ex.verticalSpeed();
        double flyDuration = ex.flyDurationSeconds();
        int teleportCount = ex.teleportCount();
        double rotationSpeed = ex.rotationSpeed();
        double attackCps = ex.attackCps();
        double placeRate = ex.placeRate();
        double breakSpeed = ex.breakSpeedRatio();
        int serverCorrection = ex.serverCorrectionCount();
        boolean nofall = ex.nofallSuspect();
        int byteAnomalies = proxy.byteAnomalyCount();
        double packetLoss = ex.packetLossRate();

        ctx.putExtended("ext_net_speed_ratio", speedRatio);
        ctx.putExtended("ext_net_vertical_speed", verticalSpeed);
        ctx.putExtended("ext_net_fly_duration", flyDuration);
        ctx.putExtended("ext_net_teleport_count", teleportCount);
        ctx.putExtended("ext_net_rotation_speed", rotationSpeed);
        ctx.putExtended("ext_net_attack_cps", attackCps);
        ctx.putExtended("ext_net_place_rate", placeRate);
        ctx.putExtended("ext_net_break_speed", breakSpeed);
        ctx.putExtended("ext_net_server_correction", serverCorrection);
        ctx.putExtended("ext_net_nofall_suspect", nofall ? 1 : 0);
        ctx.putExtended("ext_net_byte_anomaly_count", byteAnomalies);
        ctx.putExtended("ext_net_packet_loss_rate", packetLoss);

        double score = 0;
        score += threshold(speedRatio, 4.0, 60, 2.5, 35);
        score += threshold(flyDuration, 4.0, 55, 2.0, 30);
        score += threshold(teleportCount, 3.0, 40, 1.0, 20);
        if (rotationSpeed >= 1000) {
            score += 35;
        }
        if (attackCps >= 20) {
            score += 35;
        }
        if (placeRate >= 10) {
            score += 30;
        }
        if (breakSpeed >= 2.5) {
            score += 35;
        }
        score += threshold(serverCorrection, 20.0, 45, 5.0, 25);
        if (nofall) {
            score += 25;
        }
        score += threshold(byteAnomalies, 3.0, 25, 1.0, 10);
        if (packetLoss >= 0.2) {
            score += 15;
        }
        score = Math.min(100.0, score);
        ctx.putExtended("ext_net_score", score);

        if (score < THRESHOLD) {
            return Optional.empty();
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("score", score);
        detail.put("speed_ratio", speedRatio);
        detail.put("vertical_speed", verticalSpeed);
        detail.put("fly_duration", flyDuration);
        detail.put("teleport_count", teleportCount);
        detail.put("rotation_speed", rotationSpeed);
        detail.put("attack_cps", attackCps);
        detail.put("place_rate", placeRate);
        detail.put("break_speed", breakSpeed);
        detail.put("server_correction", serverCorrection);
        detail.put("nofall_suspect", nofall);
        detail.put("byte_anomaly_count", byteAnomalies);
        detail.put("packet_loss_rate", packetLoss);
        detail.put("packets", ex.packetCount());
        detail.put("proxy", proxy.status());
        return Optional.of(new DetectionEvent(
                "net_behavior_anomaly",
                score >= HIGH_THRESHOLD ? "high" : "medium",
                (int) score,
                null, null, null,
                ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    @Override
    public void onRecover() {
        proxy.stop();
    }

    /** 主链路退出时释放本地端口（与 {@link #onRecover()} 相同的复位动作）。 */
    @Override
    public void close() {
        proxy.stop();
    }

    private static double threshold(double value, double high, double highScore,
                                    double low, double lowScore) {
        if (value >= high) {
            return highScore;
        }
        if (value >= low) {
            return lowScore;
        }
        return 0;
    }
}