package com.potatotv.paccclient.redscreen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §7.3 回放封装测试：MJPEG-AVI 容器结构、水印落字、加密可逆、摘要一致、空帧拒绝。
 *
 * <p>抓屏本身（Robot）在无显示环境跑不了，也不该在 CI 里抓屏；这里验证的是「拿到帧之后」的全部逻辑，
 * 也就是真正决定产物能不能被播放、能不能被安全存储的部分。</p>
 */
class SessionRecorderTest {

    @Test
    void packagesFramesIntoPlayableAvi() throws Exception {
        List<SessionRecorder.Frame> frames = syntheticFrames(12, 64, 48);

        SessionRecorder.Recording recording =
                SessionRecorder.packageRecording("alert_1", frames, 64, 48, 5);

        byte[] avi = SessionRecorder.decrypt(recording);
        String head = new String(avi, 0, 12, StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("RIFF"), "AVI 应以 RIFF 开头：" + head);
        assertTrue(head.endsWith("AVI "), "RIFF 类型应为 AVI：" + head);
        String body = new String(avi, StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("MJPG"), "应带 MJPG 编码标识");
        assertTrue(body.contains("movi"));
        assertTrue(body.contains("idx1"), "应带索引块");
        // 每帧在 movi 里一个 00dc 块，idx1 里再一条索引：共 2 × 帧数
        assertEquals(frames.size() * 2, countOf(body, "00dc"), "每帧应有数据块与索引条目各一条");
    }

    @Test
    void recordingMetadataMatchesFrames() throws Exception {
        List<SessionRecorder.Frame> frames = syntheticFrames(6, 32, 24);

        SessionRecorder.Recording recording =
                SessionRecorder.packageRecording("alert_2", frames, 32, 24, 5);

        assertEquals(6, recording.frames());
        assertEquals(32, recording.width());
        assertEquals(24, recording.height());
        assertEquals(5, recording.fps());
        assertEquals("alert_2", recording.alertId());
        assertTrue(recording.plainSize() > 0);
        assertEquals(64, recording.sha256().length(), "摘要应是 SHA-256 十六进制");
        assertEquals(sha256Hex(SessionRecorder.decrypt(recording)), recording.sha256(),
                "摘要必须对应解密后的明文");
        assertEquals(32, recording.key().length, "AES-256 密钥");
        assertEquals(12, recording.iv().length, "GCM IV 12 字节");
        assertFalse(java.util.Arrays.equals(recording.cipher(), SessionRecorder.decrypt(recording)),
                "落盘内容必须是密文");
    }

    @Test
    void framesWithOddLengthArePaddedSoChunksStayAligned() throws Exception {
        // JPEG 长度有奇数也有偶数：奇数帧补一个填充字节，否则 AVI 解析器会错位
        List<SessionRecorder.Frame> frames = new ArrayList<>();
        frames.add(new SessionRecorder.Frame(new byte[]{1, 2, 3}, 1000L));            // 奇数
        frames.add(new SessionRecorder.Frame(new byte[]{1, 2, 3, 4}, 1200L));         // 偶数

        SessionRecorder.Recording recording =
                SessionRecorder.packageRecording("a3", frames, 4, 4, 5);
        byte[] avi = SessionRecorder.decrypt(recording);

        assertTrue(avi.length % 2 == 0, "RIFF 块按偶数字节对齐");
        assertTrue(new String(avi, StandardCharsets.ISO_8859_1).contains("00dc"));
    }

    @Test
    void watermarkDrawsVisibleText() {
        BufferedImage img = new BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 320, 200);
        g.dispose();
        int before = img.getRGB(6, 6);

        SessionRecorder.watermark(img, "PT00***01  2026-09-25T10:00:00Z");

        assertFalse(before == img.getRGB(6, 6), "水印区域应被改写（可回溯 PTEID 与时间）");
    }

    @Test
    void emptyFramesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SessionRecorder.packageRecording("a4", List.of(), 10, 10, 5));
        assertThrows(IllegalArgumentException.class,
                () -> SessionRecorder.packageRecording("a4", null, 10, 10, 5));
    }

    @Test
    void replayIsOffByDefault() {
        assertFalse(SessionRecorder.enabled(), "未显式开启 PACC_REPLAY_ENABLED 时不得采集屏幕");
    }

    // ------------------------------ 测试数据 ------------------------------

    private static List<SessionRecorder.Frame> syntheticFrames(int count, int width, int height) throws Exception {
        List<SessionRecorder.Frame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setColor(new Color(i * 20 % 255, 80, 160));
            g.fillRect(0, 0, width, height);
            g.dispose();
            frames.add(new SessionRecorder.Frame(toJpeg(img), 1000L + i * 200L));
        }
        return frames;
    }

    private static byte[] toJpeg(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", out);
        return out.toByteArray();
    }

    private static int countOf(String text, String token) {
        int count = 0;
        int i = 0;
        while ((i = text.indexOf(token, i)) >= 0) {
            count++;
            i += token.length();
        }
        return count;
    }

    private static String sha256Hex(byte[] data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}