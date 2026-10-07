package com.potatotv.paccclient.detection.vision;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import org.junit.jupiter.api.Test;

/** {@link HudDetector} 合成图测试：不依赖真实屏幕。 */
class HudDetectorTest {

    @Test
    void 中空矩形被识别为自瞄方框() {
        BufferedImage img = grayCanvas(320, 180);
        drawHollowRect(img, 140, 60, 30, 50);

        HudDetector.VisionAnalysis a = new HudDetector().analyze(img);

        assertTrue(a.hudBoxCount() >= 1, "应至少识别出 1 个中空矩形，实际=" + a.hudBoxCount());
    }

    @Test
    void 长竖线被计为透视长线() {
        BufferedImage img = grayCanvas(320, 180);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i < 8; i++) {
            int x = 20 + i * 35;
            g.drawLine(x, 30, x, 150);
        }
        g.dispose();

        HudDetector.VisionAnalysis a = new HudDetector().analyze(img);

        assertTrue(a.espLines() > 5, "8 条 120px 竖线应 >5，实际=" + a.espLines());
    }

    @Test
    void 全黑图判为截屏异常() {
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);

        HudDetector.VisionAnalysis a = new HudDetector().analyze(img);

        assertTrue(a.screenshotAnomaly());
    }

    @Test
    void 白噪声图不判为截屏异常() {
        BufferedImage img = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Random r = new Random(42);
        for (int y = 0; y < 180; y++) {
            for (int x = 0; x < 320; x++) {
                int v = r.nextInt(256);
                img.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }

        HudDetector.VisionAnalysis a = new HudDetector().analyze(img);

        assertFalse(a.screenshotAnomaly());
    }

    static BufferedImage grayCanvas(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(128, 128, 128));
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    static void drawHollowRect(BufferedImage img, int x, int y, int w, int h) {
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2f));
        g.drawRect(x, y, w, h);
        g.dispose();
    }
}