package com.potatotv.paccclient.detection.vision;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 屏幕层实时抓屏（文档 §3.2）：默认 2fps，等比缩放到宽度上限 1280，只保留最新 4 帧。
 *
 * <p>抓屏是可选增强：headless（无图形环境）或 {@link Robot} 不可用时 {@link #start()} 返回
 * {@code false} 并安静降级，绝不把客户端带崩；单帧抓屏失败（锁屏 / 权限 / RDP 切换）只跳过该帧。</p>
 *
 * <p><b>隐私边界</b>：本类只负责把画面交给 {@link HudDetector} 做本地数字特征提取，
 * 原始帧既不落盘也不上传；{@link #latestFrame()} 取走即从队列移除。</p>
 *
 * <p><b>测试接缝</b>：包级构造器允许注入帧源，测试据此注入合成帧、完全不碰真实屏幕，
 * 生产路径用默认构造器创建 {@link Robot} 帧源。</p>
 */
public final class ScreenCapture implements AutoCloseable {

    /** 帧率（文档 §3.2，1-2fps 足够识别 HUD 元素）。 */
    static final int FPS = 2;
    /** 单帧缩放宽度上限：控制分析成本，等比缩放。 */
    static final int MAX_WIDTH = 1280;
    /** 帧队列容量：分析端落后时只保留最新几帧。 */
    static final int QUEUE_CAPACITY = 4;

    private final Supplier<Optional<BufferedImage>> injectedSource;
    private final Deque<BufferedImage> queue = new ArrayDeque<>(QUEUE_CAPACITY);
    private final Object lock = new Object();

    private volatile boolean running;
    private ScheduledExecutorService scheduler;

    /** 生产用法：{@link Robot} 抓屏。 */
    public ScreenCapture() {
        this.injectedSource = null;
    }

    /** 测试接缝：注入帧源（返回空表示本帧跳过）。 */
    ScreenCapture(Supplier<Optional<BufferedImage>> frameSource) {
        this.injectedSource = frameSource;
    }

    /**
     * 启动后台抓屏（单线程守护调度）。已启动时直接返回 {@code true}。
     *
     * @return 是否真的启动了抓屏；headless / Robot 不可用时 {@code false}
     */
    public boolean start() {
        if (running) {
            return true;
        }
        Supplier<Optional<BufferedImage>> source = injectedSource;
        if (source == null) {
            if (GraphicsEnvironment.isHeadless()) {
                System.out.println("[PTV-Client] 屏幕视觉未启动：当前环境无图形界面");
                return false;
            }
            try {
                Robot robot = new Robot();
                source = () -> captureWithRobot(robot);
            } catch (Exception e) {
                System.out.println("[PTV-Client] 屏幕视觉未启动：抓屏不可用 " + e.getMessage());
                return false;
            }
        }
        final Supplier<Optional<BufferedImage>> src = source;
        running = true;
        // 同步抓第一帧：保证 start() 返回后 latestFrame() 立即可用（也让注入帧源的测试可确定性断言）
        captureOnce(src);
        scheduler = Executors.newSingleThreadScheduledExecutor(
                r -> Thread.ofPlatform().name("ptv-vision-capture").daemon(true).unstarted(r));
        scheduler.scheduleWithFixedDelay(() -> captureOnce(src), 1000L / FPS, 1000L / FPS, TimeUnit.MILLISECONDS);
        return true;
    }

    /** 取走最新一帧（旧帧全部丢弃）；队列为空返回 {@link Optional#empty()}。 */
    public Optional<BufferedImage> latestFrame() {
        synchronized (lock) {
            BufferedImage latest = null;
            while (!queue.isEmpty()) {
                latest = queue.pollFirst();
            }
            return Optional.ofNullable(latest);
        }
    }

    /** 是否正在抓屏。 */
    public boolean running() {
        return running;
    }

    /** 停止抓屏并释放调度线程。 */
    @Override
    public void close() {
        running = false;
        ScheduledExecutorService s = scheduler;
        scheduler = null;
        if (s != null) {
            s.shutdownNow();
        }
        synchronized (lock) {
            queue.clear();
        }
    }

    private void captureOnce(Supplier<Optional<BufferedImage>> source) {
        if (!running) {
            return;
        }
        try {
            Optional<BufferedImage> frame = source.get();
            if (frame == null || frame.isEmpty()) {
                return;
            }
            BufferedImage image = frame.get();
            synchronized (lock) {
                queue.addLast(image);
                while (queue.size() > QUEUE_CAPACITY) {
                    queue.removeFirst();
                }
            }
        } catch (Throwable t) {
            // 抓屏失败（锁屏 / 权限 / RDP 切换）只跳过本帧
        }
    }

    private static Optional<BufferedImage> captureWithRobot(Robot robot) {
        try {
            Dimension size = Toolkit.getDefaultToolkit().getScreenSize();
            BufferedImage shot = robot.createScreenCapture(new Rectangle(size));
            return Optional.of(scaleToWidth(shot, MAX_WIDTH));
        } catch (Throwable t) {
            return Optional.empty();
        }
    }

    private static BufferedImage scaleToWidth(BufferedImage src, int maxWidth) {
        if (src.getWidth() <= maxWidth) {
            return src;
        }
        int w = maxWidth;
        int h = Math.max(1, (int) Math.round(src.getHeight() * (double) maxWidth / src.getWidth()));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}