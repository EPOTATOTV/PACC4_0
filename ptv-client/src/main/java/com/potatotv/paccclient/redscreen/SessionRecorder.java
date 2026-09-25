package com.potatotv.paccclient.redscreen;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * v5.2 §7.3 查端回放：常驻环形缓冲录制，红屏时导出「前 30 秒 + 后 60 秒」并加密上报。
 *
 * <p><b>录制范围</b>：只录屏幕（默认整屏按 960px 等比缩放），帧上烧入 {@code PTEID + 时间戳} 水印；
 * 可用环境变量 {@code PACC_REPLAY_REGION=x,y,w,h} 把范围限制到游戏窗口，运维应配合桌面壳把游戏窗口位置
 * 写进该变量——纯 JDK 拿不到「哪个窗口是 Minecraft」，这里不猜、不假装。</p>
 *
 * <p><b>隐私与开关（§10.3）</b>：默认关闭，需 {@code PACC_REPLAY_ENABLED=true} 才启动采集；
 * 红屏界面已有明确告知文案；产物为 MJPEG-AVI，<b>先加密再上传</b>（AES-256-GCM，密钥随元数据经 HTTPS 提交），
 * 服务端保留 30 天后自动删除；单条产物超过 {@value #MB_LIMIT}MB 时按帧丢弃最老片段，
 * 宁可短一点也不要上传超限文件。</p>
 *
 * <p><b>线程模型</b>：采集是独立守护线程（5fps 抓屏 + JPEG 编码，实测单帧毫秒级），
 * 红屏导出在虚拟线程里等满 60 秒再封装上传，主检测链路完全不受影响。</p>
 */
public final class SessionRecorder {

    /** 帧率（文档 §7.3 建议 5fps）。 */
    static final int FPS = 5;
    /** 红屏前保留时长（秒）。 */
    static final int PRE_SECONDS = 30;
    /** 红屏后继续录制时长（秒）。 */
    static final int POST_SECONDS = 60;
    /** 单条产物上限（MB，文档验收 &lt;50MB）。 */
    static final int MB_LIMIT = 50;
    /** 采集宽度上限：等比缩放到该宽度，控制 JPEG 体积。 */
    private static final int MAX_WIDTH = 960;
    private static final float JPEG_QUALITY = 0.6f;
    private static final long MAX_BYTES = (long) MB_LIMIT * 1024 * 1024;

    /** 一帧。 */
    record Frame(byte[] jpeg, long timestampMillis) {
    }

    /** 成品录像（密文 + 解密所需元数据 + 校验摘要）。 */
    public record Recording(String alertId, byte[] cipher, byte[] key, byte[] iv,
                            int frames, int width, int height, int fps, long durationMillis,
                            long plainSize, String sha256) {
    }

    private final String pteidMasked;
    private final Deque<Frame> ring = new ArrayDeque<>();
    private final Object lock = new Object();

    private ScheduledExecutorService captureThread;
    private volatile boolean capturing;
    private volatile int frameWidth;
    private volatile int frameHeight;

    public SessionRecorder(String pteidMasked) {
        this.pteidMasked = pteidMasked == null ? "****" : pteidMasked;
    }

    /** 是否开启采集（环境变量 {@code PACC_REPLAY_ENABLED}；缺省关闭）。 */
    public static boolean enabled() {
        String v = System.getenv("PACC_REPLAY_ENABLED");
        return v != null && (v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("on"));
    }

    /**
     * 启动环形缓冲采集。headless（无图形环境）或 Robot 不可用时安静跳过——回放是可选增强，
     * 不能让它在服务器/容器里把客户端带崩。
     *
     * @return 是否真的启动了采集
     */
    public boolean startRingBuffer() {
        if (capturing || !enabled()) return false;
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("[PTV-Client] 查端回放未启动：当前环境无图形界面");
            return false;
        }
        Robot robot;
        try {
            robot = new Robot();
        } catch (Exception e) {
            System.out.println("[PTV-Client] 查端回放未启动：抓屏不可用 " + e.getMessage());
            return false;
        }
        Rectangle region = captureRegion();
        capturing = true;
        captureThread = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("ptv-replay").daemon(true).unstarted(r));
        captureThread.scheduleWithFixedDelay(() -> captureOnce(robot, region),
                0, 1000L / FPS, TimeUnit.MILLISECONDS);
        System.out.println("[PTV-Client] 查端回放已启动 " + region.width + "x" + region.height + " @" + FPS + "fps");
        return true;
    }

    /** 停止采集（进程退出时调用）。 */
    public void stop() {
        capturing = false;
        ScheduledExecutorService s = captureThread;
        captureThread = null;
        if (s != null) s.shutdownNow();
    }

    /**
     * 红屏触发：带上已有缓冲继续录 {@value #POST_SECONDS} 秒，然后封装并回调上传。
     *
     * @param alertId 关联红屏告警 id（服务端按它归档）
     * @param onReady 录像就绪后的回调（在虚拟线程里执行，异常由调用方自行处理）
     * @return 是否已开始导出（未开启采集时返回 false）
     */
    public boolean onRedScreen(String alertId, Consumer<Recording> onReady) {
        if (!capturing) return false;
        String id = alertId == null || alertId.isBlank() ? "manual" : alertId;
        Thread.ofVirtual().name("ptv-replay-export").start(() -> {
            try {
                Thread.sleep(POST_SECONDS * 1000L);
                List<Frame> frames = snapshotFrames();
                Recording recording = packageRecording(id, frames, frameWidth, frameHeight, FPS);
                if (onReady != null) onReady.accept(recording);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.err.println("[PTV-Client] 查端回放导出失败: " + e.getMessage());
            }
        });
        System.out.println("[PTV-Client] 查端回放导出已开始 alert=" + id
                + "（含前 " + PRE_SECONDS + "s，继续录 " + POST_SECONDS + "s）");
        return true;
    }

    /** 当前缓冲帧数（供本地控制服务/联调观测）。 */
    public int bufferedFrames() {
        synchronized (lock) {
            return ring.size();
        }
    }

    // ------------------------------ 采集 ------------------------------

    private void captureOnce(Robot robot, Rectangle region) {
        if (!capturing) return;
        try {
            BufferedImage shot = robot.createScreenCapture(region);
            BufferedImage scaled = scaleToWidth(shot, MAX_WIDTH);
            frameWidth = scaled.getWidth();
            frameHeight = scaled.getHeight();
            watermark(scaled, pteidMasked + "  " + Instant.now());
            byte[] jpeg = toJpeg(scaled);
            if (jpeg == null) return;
            synchronized (lock) {
                ring.addLast(new Frame(jpeg, System.currentTimeMillis()));
                trimRing();
            }
        } catch (Throwable t) {
            // 抓屏失败（锁屏 / 权限 / RDP 切换）只跳过本帧
        }
    }

    /** 只保留「前 30 秒 + 后 60 秒」窗口内的帧。 */
    private void trimRing() {
        int keep = FPS * (PRE_SECONDS + POST_SECONDS);
        while (ring.size() > keep) ring.removeFirst();
    }

    private List<Frame> snapshotFrames() {
        synchronized (lock) {
            return new ArrayList<>(ring);
        }
    }

    /** 采集区域：{@code PACC_REPLAY_REGION=x,y,w,h} 优先（桌面壳把游戏窗口位置写进来），否则整屏。 */
    private static Rectangle captureRegion() {
        String raw = System.getenv("PACC_REPLAY_REGION");
        if (raw != null && !raw.isBlank()) {
            String[] parts = raw.split(",");
            if (parts.length == 4) {
                try {
                    int x = Integer.parseInt(parts[0].trim());
                    int y = Integer.parseInt(parts[1].trim());
                    int w = Integer.parseInt(parts[2].trim());
                    int h = Integer.parseInt(parts[3].trim());
                    if (w > 0 && h > 0) return new Rectangle(x, y, w, h);
                } catch (NumberFormatException e) {
                    // 配置写错就退回整屏
                }
            }
        }
        return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
    }

    private static BufferedImage scaleToWidth(BufferedImage src, int maxWidth) {
        if (src.getWidth() <= maxWidth) return src;
        int w = maxWidth;
        int h = Math.max(1, (int) Math.round(src.getHeight() * (double) maxWidth / src.getWidth()));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** 烧入水印（PTEID + 时间戳）：录像流出后仍可回溯属于谁、什么时间。 */
    static void watermark(BufferedImage img, String text) {
        Graphics2D g = img.createGraphics();
        try {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(12, img.getWidth() / 60)));
            int pad = 4;
            int textWidth = g.getFontMetrics().stringWidth(text);
            int textHeight = g.getFontMetrics().getHeight();
            g.setColor(new Color(0, 0, 0, 140));
            g.fillRect(0, 0, textWidth + pad * 2, textHeight + pad);
            g.setColor(new Color(255, 255, 255, 230));
            g.drawString(text, pad, textHeight - pad);
        } finally {
            g.dispose();
        }
    }

    private static byte[] toJpeg(BufferedImage img) {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) return null;
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(img.getWidth() * img.getHeight() / 8);
             MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.setOutput(ios);
            writer.write(null, new IIOImage(img, null, null), param);
            writer.dispose();
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------ 封装（可单测） ------------------------------

    /**
     * 封装：MJPEG-AVI → 超限截断 → 加密 → 摘要。
     *
     * @throws IllegalArgumentException 没有任何帧
     */
    static Recording packageRecording(String alertId, List<Frame> frames, int width, int height, int fps) {
        if (frames == null || frames.isEmpty()) throw new IllegalArgumentException("没有可导出的帧");
        List<byte[]> jpegs = new ArrayList<>(frames.size());
        long bytes = 0;
        for (Frame f : frames) bytes += f.jpeg().length;
        // 超过上限时从最老的帧开始丢弃，优先保留红屏后的近况
        int from = 0;
        while (bytes > MAX_BYTES && from < frames.size() - FPS * 10) {
            bytes -= frames.get(from).jpeg().length;
            from++;
        }
        for (int i = from; i < frames.size(); i++) jpegs.add(frames.get(i).jpeg());
        long duration = frames.isEmpty() ? 0
                : frames.get(frames.size() - 1).timestampMillis() - frames.get(from).timestampMillis();
        byte[] avi = MjpegAvi.encode(jpegs, fps, width, height);
        ReplayCipher.Sealed sealed = ReplayCipher.seal(avi);
        return new Recording(alertId, sealed.cipher(), sealed.key(), sealed.iv(),
                jpegs.size(), width, height, fps, duration, avi.length, sha256Hex(avi));
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 供服务端/测试解密校验（与 {@link ReplayCipher} 同实现）。 */
    public static byte[] decrypt(Recording recording) {
        return ReplayCipher.open(recording.cipher(), recording.key(), recording.iv());
    }
}