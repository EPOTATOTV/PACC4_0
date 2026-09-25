package com.potatotv.pacc.service.v53;

import com.potatotv.pacc.service.detection.v52.ReplayStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * v5.3 §2.4 查端回放在线预览：把加密录像里的一帧解出来、盖上操作者水印，再以 JPEG 返回。
 *
 * <p>为什么不直接播 AVI：录像明文是 MJPEG-AVI，浏览器不认 AVI 容器（H.264/VP9 之外基本不解析），
 * 所以在线播放改成「逐帧 JPEG 拉取 + 前端按 fps 播放」，抽帧仍在服务端解密后完成，
 * 磁盘上依旧只有密文，密钥不下发。</p>
 *
 * <p>水印是防泄露的硬要求：每一帧都烧上操作者、录像 id 与查看时间；调用方必须传入真实操作者，
 * 不允许匿名浏览。</p>
 */
@Service
@RequiredArgsConstructor
public class ReplayFrameService {

    /** 单帧 JPEG 的最小合法长度（SOI+EOI 计 4 字节，短于此必是坏数据）。 */
    private static final int MIN_JPEG_LENGTH = 4;

    /** 水印斜排的固定角度（弧度）。 */
    private static final double WATERMARK_ANGLE = -Math.PI / 7;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ReplayStorageService replayStorageService;

    /** 抽帧结果：JPEG 字节 + 帧序号 + 本录像的真实帧总数（解出来才算，不信上传时的自述值）。 */
    public record Frame(byte[] jpeg, int index, int total) {
    }

    /**
     * 解密录像并取出第 index 帧，叠加操作者水印后返回 JPEG。
     *
     * @param id    录像 id
     * @param index 帧序号（0 起）
     * @param actor 操作者标识
     * @throws NoSuchElementException 录像不存在 / 文件缺失 / 帧序号越界
     * @throws IllegalStateException  解密失败或帧无法解码
     */
    public Frame frame(String id, int index, String actor) {
        byte[] avi = replayStorageService.open(id);
        List<int[]> spans = jpegSpans(avi);
        if (spans.isEmpty()) {
            throw new IllegalStateException("录像里没有可解码的 MJPEG 帧");
        }
        if (index < 0 || index >= spans.size()) {
            throw new NoSuchElementException("帧序号越界（本录像共 " + spans.size() + " 帧）");
        }
        int[] span = spans.get(index);
        byte[] raw = Arrays.copyOfRange(avi, span[0], span[1]);
        BufferedImage watermarked = watermark(decode(raw), actor, id);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64 * 1024, raw.length));
            ImageIO.write(watermarked, "jpg", out);
            return new Frame(out.toByteArray(), index, spans.size());
        } catch (IOException e) {
            throw new IllegalStateException("帧编码失败：" + e.getMessage(), e);
        }
    }

    /** 按 SOI(FFD8FF)…EOI(FFD9) 扫描出各帧的字节区间；MJPEG 每帧就是一张独立 JPEG。 */
    static List<int[]> jpegSpans(byte[] data) {
        List<int[]> spans = new ArrayList<>();
        if (data == null || data.length < MIN_JPEG_LENGTH) return spans;
        int i = 0;
        while (i + 3 < data.length) {
            if ((data[i] & 0xFF) != 0xFF || (data[i + 1] & 0xFF) != 0xD8 || (data[i + 2] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int end = -1;
            for (int scan = i + 3; scan + 1 < data.length; scan++) {
                if ((data[scan] & 0xFF) == 0xFF && (data[scan + 1] & 0xFF) == 0xD9) {
                    end = scan + 2;
                    break;
                }
            }
            if (end < 0) break; // 没有 EOI，后面的数据不完整
            spans.add(new int[]{i, end});
            i = end;
        }
        return spans;
    }

    private static BufferedImage decode(byte[] frame) {
        try (ByteArrayInputStream in = new ByteArrayInputStream(frame)) {
            BufferedImage img = ImageIO.read(in);
            if (img == null) throw new IllegalStateException("帧解码失败（不是合法 JPEG）");
            return img;
        } catch (IOException e) {
            throw new IllegalStateException("帧解码失败：" + e.getMessage(), e);
        }
    }

    /** 斜排平铺水印：操作者 · 录像 id 前 8 位 · 查看时间。 */
    private BufferedImage watermark(BufferedImage src, String actor, String id) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, null);

            String who = (actor == null || actor.isBlank()) ? "unknown" : actor;
            String shortId = (id == null || id.length() < 8) ? "" : id.substring(0, 8);
            String text = who + " · " + shortId + " · " + LocalDateTime.now().format(STAMP);

            int size = Math.max(13, Math.min(Math.round(h / 24f), 30));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, size));

            AffineTransform origin = g.getTransform();
            g.rotate(WATERMARK_ANGLE, w / 2.0, h / 2.0);
            int stepY = size * 5;
            int stepX = size * 12;
            for (int y = -h; y < h * 2; y += stepY) {
                for (int x = -w; x < w * 2; x += stepX) {
                    // 先画深色描边保证浅色画面上也能看清，再叠半透明白字
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
                    g.setColor(Color.BLACK);
                    g.drawString(text, x + 1, y + 1);
                    g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.30f));
                    g.setColor(Color.WHITE);
                    g.drawString(text, x, y);
                }
            }
            g.setTransform(origin);
        } finally {
            g.dispose();
        }
        return canvas;
    }
}