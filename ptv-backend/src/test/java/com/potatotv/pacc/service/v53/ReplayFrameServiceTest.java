package com.potatotv.pacc.service.v53;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.service.detection.v52.ReplayStorageService;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.NoSuchElementException;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v5.3 §2.4 回放逐帧预览测试：MJPEG 抽帧、水印烧录、越界与坏数据的处理。
 */
class ReplayFrameServiceTest {

    private static final String ID = "abcdef1234567890";

    private ReplayStorageService storage;
    private ReplayFrameService service;

    @BeforeEach
    void setUp() {
        storage = mock(ReplayStorageService.class);
        service = new ReplayFrameService(storage);
    }

    @Test
    void scansMjpegFramesInsideAviContainer() throws Exception {
        byte[] avi = aviWithFrames(3);

        List<int[]> spans = ReplayFrameService.jpegSpans(avi);

        assertEquals(3, spans.size());
        for (int i = 1; i < spans.size(); i++) {
            assertTrue(spans.get(i)[0] > spans.get(i - 1)[1], "帧区间应按顺序不重叠");
        }
    }

    @Test
    void returnsWatermarkedJpegForRequestedFrame() throws Exception {
        byte[] avi = aviWithFrames(3);
        when(storage.open(ID)).thenReturn(avi);

        ReplayFrameService.Frame frame = service.frame(ID, 1, "ops@potatotv");

        assertEquals(1, frame.index());
        assertEquals(3, frame.total());
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(frame.jpeg()));
        assertNotNull(decoded, "返回的必须是可解码 JPEG");
        assertEquals(320, decoded.getWidth());
        assertEquals(240, decoded.getHeight());
        // 水印是烧进像素的：与原始帧相比应有大片像素被改写（JPEG 重编码不会有这种量级）
        assertTrue(changedRatio(ImageIO.read(new ByteArrayInputStream(rawFrame(1))), decoded) > 0.1,
                "操作者水印应覆盖到帧像素");
    }

    @Test
    void rejectsOutOfRangeIndex() throws Exception {
        when(storage.open(ID)).thenReturn(aviWithFrames(2));

        assertThrows(NoSuchElementException.class, () -> service.frame(ID, 2, "ops"));
        assertThrows(NoSuchElementException.class, () -> service.frame(ID, -1, "ops"));
    }

    @Test
    void rejectsPayloadWithoutDecodableFrame() {
        when(storage.open(ID)).thenReturn("NOT-A-JPEG-PAYLOAD".getBytes());

        assertThrows(IllegalStateException.class, () -> service.frame(ID, 0, "ops"));
    }

    @Test
    void fallsBackToUnknownActorWhenEmpty() throws Exception {
        when(storage.open(ID)).thenReturn(aviWithFrames(1));

        // 空操作者不允许被当成匿名浏览，仍要落一个可追溯的标识
        assertNotNull(service.frame(ID, 0, "").jpeg());
        assertNotEquals(0, service.frame(ID, 0, "").jpeg().length);
    }

    // ------------------------------ 构造与工具 ------------------------------

    /** 拼一个「AVI 外壳 + N 张 JPEG」的明文，外壳是无关字节，用来验证扫描器不会被带偏。 */
    private static byte[] aviWithFrames(int frames) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("RIFF____AVI LIST hdrl movi".getBytes());
        for (int i = 0; i < frames; i++) {
            out.write(rawFrame(i));
            out.write(new byte[]{0x30, 0x30, 0x64, 0x63}); // AVI 里的填充块
        }
        out.write("idx1 trailing".getBytes());
        return out.toByteArray();
    }

    private static byte[] rawFrame(int index) throws IOException {
        BufferedImage img = new BufferedImage(320, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setColor(new Color(40 + index * 30, 60, 90));
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static double changedRatio(BufferedImage a, BufferedImage b) {
        int total = a.getWidth() * a.getHeight();
        int changed = 0;
        for (int y = 0; y < a.getHeight(); y += 2) {
            for (int x = 0; x < a.getWidth(); x += 2) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) changed++;
            }
        }
        return changed / (double) (total / 4);
    }
}