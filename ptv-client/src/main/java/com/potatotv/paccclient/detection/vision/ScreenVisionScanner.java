package com.potatotv.paccclient.detection.vision;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.PerfToggles;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 屏幕视觉检测器（文档 §3.2 / §3.3）：周期抓屏 → {@link HudDetector} 提取 HUD 与运动特征 →
 * 多信号加权打分，达到阈值产出 {@code vision_anomaly}。
 *
 * <p>受 {@link PerfToggles#VISION}（键 {@code "vision"}，默认关闭）控制：关闭时直接空返回，
 * 且不启动抓屏线程。</p>
 *
 * <p>打分（文档 §3.3，cap 100）：自瞄方框 ≥1 → 35、≥3 再加 25；透视长线 &gt;5 → 45；
 * 杀戮光环 → 30；作弊菜单文字 &gt;10 → 20；运动不匹配 → 25；截屏异常 → 5。
 * ≥50 产出事件（≥75 high，否则 medium），否则只写扩展特征。</p>
 *
 * <p>实现 {@link AutoCloseable}：主链路退出时 {@link #close()} 释放抓屏线程（与 {@link #onRecover()} 同动作）。</p>
 */
public final class ScreenVisionScanner implements Detector, AutoCloseable {

    private static final String ID = "screen_vision_scanner";
    private static final long INTERVAL_MS = 2_000L;
    private static final int THRESHOLD = 50;
    private static final int HIGH_THRESHOLD = 75;
    /** 网络速度倍率 → 格/s 的换算系数。 */
    private static final double SPEED_PER_RATIO = 5.6;

    private final ScreenCapture capture;
    private final HudDetector detector;

    public ScreenVisionScanner() {
        this(new ScreenCapture(), new HudDetector());
    }

    /** 测试接缝：注入抓屏与检测器，完全不碰真实屏幕。 */
    ScreenVisionScanner(ScreenCapture capture, HudDetector detector) {
        this.capture = capture;
        this.detector = detector;
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
        if (!PerfToggles.enabled(PerfToggles.VISION)) {
            return Optional.empty();
        }
        if (!capture.running() && !capture.start()) {
            // 抓屏不可用（headless / Robot 缺失）属于能力缺失，不是检测到的异常：
            // 写 0 而非 1，避免把「没这个能力」误报成「画面异常」。
            ctx.putExtended("ext_vision_screenshot_anomaly", 0);
            ctx.putExtended("ext_vision_score", 0);
            return Optional.empty();
        }
        Optional<BufferedImage> frame = capture.latestFrame();
        if (frame.isEmpty()) {
            return Optional.empty();
        }

        HudDetector.VisionAnalysis analysis = detector.analyze(frame.get());
        double networkSpeed = ctx.features().get("ext_net_speed_ratio") * SPEED_PER_RATIO;
        boolean motionMismatch = detector.motionMismatch(analysis.motionMagnitude(), networkSpeed);
        int score = computeScore(analysis, motionMismatch);

        ctx.putExtended("ext_vision_hud_box_count", analysis.hudBoxCount());
        ctx.putExtended("ext_vision_esp_lines", analysis.espLines());
        ctx.putExtended("ext_vision_killaura_circle", analysis.killauraCircle() ? 1 : 0);
        ctx.putExtended("ext_vision_cheat_menu_text", analysis.cheatMenuText());
        ctx.putExtended("ext_vision_motion_mismatch", motionMismatch ? 1 : 0);
        ctx.putExtended("ext_vision_screenshot_anomaly", analysis.screenshotAnomaly() ? 1 : 0);
        ctx.putExtended("ext_vision_score", score);

        if (score < THRESHOLD) {
            return Optional.empty();
        }

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("hud_box_count", analysis.hudBoxCount());
        detail.put("esp_lines", analysis.espLines());
        detail.put("killaura_circle", analysis.killauraCircle());
        detail.put("cheat_menu_text", analysis.cheatMenuText());
        detail.put("motion_mismatch", motionMismatch);
        detail.put("motion_magnitude", Math.round(analysis.motionMagnitude() * 100.0) / 100.0);
        detail.put("screenshot_anomaly", analysis.screenshotAnomaly());
        detail.put("score", score);
        return Optional.of(new DetectionEvent(
                "vision_anomaly",
                score >= HIGH_THRESHOLD ? "high" : "medium",
                score,
                null, null, null, ctx.osInfo().summary(),
                Json.encode(detail)));
    }

    @Override
    public void onRecover() {
        capture.close();
    }

    /** 主链路退出时释放抓屏线程。 */
    @Override
    public void close() {
        capture.close();
    }

    private static int computeScore(HudDetector.VisionAnalysis a, boolean motionMismatch) {
        int score = 0;
        if (a.hudBoxCount() >= 1) {
            score += 35;
        }
        if (a.hudBoxCount() >= 3) {
            score += 25;
        }
        if (a.espLines() > 5) {
            score += 45;
        }
        if (a.killauraCircle()) {
            score += 30;
        }
        if (a.cheatMenuText() > 10) {
            score += 20;
        }
        if (motionMismatch) {
            score += 25;
        }
        if (a.screenshotAnomaly()) {
            score += 5;
        }
        return Math.min(100, score);
    }
}