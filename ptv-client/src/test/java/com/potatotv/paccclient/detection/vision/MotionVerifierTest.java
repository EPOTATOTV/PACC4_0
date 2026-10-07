package com.potatotv.paccclient.detection.vision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

/** 画面运动验证测试（文档 §3.3.2）：块差 + motionMismatch 阈值。 */
class MotionVerifierTest {

    @Test
    void 相同帧运动量接近零且与网络速度不匹配() {
        HudDetector detector = new HudDetector();
        detector.analyze(scene(0));
        HudDetector.VisionAnalysis second = detector.analyze(scene(0));

        assertTrue(second.motionMagnitude() < 2.0, "相同帧运动量应≈0，实际=" + second.motionMagnitude());
        assertTrue(detector.motionMismatch(second.motionMagnitude(), 12.0),
                "画面不动 + 网络速度 12 > 10 应判定不匹配");
    }

    @Test
    void 明显位移运动量大且与网络速度匹配() {
        HudDetector detector = new HudDetector();
        detector.analyze(scene(0));
        HudDetector.VisionAnalysis second = detector.analyze(scene(40));

        assertTrue(second.motionMagnitude() > 5.0, "明显位移运动量应较大，实际=" + second.motionMagnitude());
        assertFalse(detector.motionMismatch(second.motionMagnitude(), 12.0),
                "画面大幅运动不应判定为不匹配");
    }

    /** 灰底 + 一条全高黑条，offset 控制横向位移。 */
    private static BufferedImage scene(int offset) {
        BufferedImage img = HudDetectorTest.grayCanvas(320, 180);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(100 + offset, 0, 50, 180);
        g.dispose();
        return img;
    }
}