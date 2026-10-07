package com.potatotv.paccclient.detection.vision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.PerfToggles;
import com.potatotv.paccclient.probe.OsInfo;
import com.potatotv.paccclient.probe.PortableSystemProbe;
import com.potatotv.paccclient.spi.DetectContext;

import java.awt.image.BufferedImage;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * {@link ScreenVisionScanner} 测试：注入合成帧，完全不启动 {@code Robot}（CI 无显示会抛）。
 */
class ScreenVisionScannerTest {

    @Test
    void 三个自瞄方框写入特征并触发() {
        ScreenCapture capture = new ScreenCapture(() -> Optional.of(threeBoxes()));
        ScreenVisionScanner scanner = new ScreenVisionScanner(capture, new HudDetector());
        PerfToggles.set(PerfToggles.VISION, true);
        try {
            DetectContext ctx = context();

            scanner.detect(ctx);

            assertTrue(ctx.features().get("ext_vision_hud_box_count") >= 3,
                    "应识别出 ≥3 个中空矩形，实际=" + ctx.features().get("ext_vision_hud_box_count"));
            assertTrue(ctx.features().get("ext_vision_score") >= 60,
                    "3 个方框应得 ≥60 分，实际=" + ctx.features().get("ext_vision_score"));
        } finally {
            PerfToggles.set(PerfToggles.VISION, false);
            scanner.close();
        }
    }

    @Test
    void 开关关闭时不启动抓屏且空返回() {
        ScreenCapture capture = new ScreenCapture(() -> Optional.of(threeBoxes()));
        ScreenVisionScanner scanner = new ScreenVisionScanner(capture, new HudDetector());
        PerfToggles.set(PerfToggles.VISION, false);
        try {
            DetectContext ctx = context();

            assertTrue(scanner.detect(ctx).isEmpty());
            assertFalse(capture.running(), "关闭时不应启动抓屏");
        } finally {
            PerfToggles.set(PerfToggles.VISION, false);
            scanner.close();
        }
    }

    private static DetectContext context() {
        return new DetectContext(new PortableSystemProbe(new OsInfo("Test", "1.0", "amd64")),
                new FeatureVector());
    }

    private static BufferedImage threeBoxes() {
        BufferedImage img = HudDetectorTest.grayCanvas(320, 180);
        HudDetectorTest.drawHollowRect(img, 80, 30, 30, 40);
        HudDetectorTest.drawHollowRect(img, 180, 30, 30, 40);
        HudDetectorTest.drawHollowRect(img, 80, 110, 30, 40);
        return img;
    }
}