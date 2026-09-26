package com.potatotv.pcu.platform;

import java.awt.AWTException;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.util.logging.Logger;

/**
 * 桌面平台的通知栏提示（设计文档 §4.7.1 {@code showUpdateNotification}）。
 *
 * <p>托盘不可用（无桌面环境、Linux 缺少托盘实现、AWT 初始化失败）时退化为写日志，
 * 绝不让「通知发不出去」把更新流程带崩。</p>
 */
final class DesktopNotifier {

    private static final Logger LOG = Logger.getLogger(DesktopNotifier.class.getName());

    /** 托盘图标全局只挂一次，避免每次通知都往托盘里塞一个图标。 */
    private static TrayIcon icon;

    private DesktopNotifier() {
    }

    static synchronized void notify(String title, String message) {
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            LOG.info(() -> "[更新通知] " + title + "：" + message);
            return;
        }
        try {
            if (icon == null) {
                icon = new TrayIcon(placeholderIcon(), "PACC");
                icon.setImageAutoSize(true);
                SystemTray.getSystemTray().add(icon);
            }
            icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
        } catch (AWTException | RuntimeException e) {
            LOG.warning(() -> "系统通知不可用，改为日志输出：" + e.getMessage());
            LOG.info(() -> "[更新通知] " + title + "：" + message);
        }
    }

    /** 通知栏需要一个图标，但没有预制图标资源可用，就画一个 16×16 的实心方块。 */
    private static Image placeholderIcon() {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = image.createGraphics();
        try {
            g.setColor(java.awt.Color.DARK_GRAY);
            g.fillRect(2, 2, 12, 12);
        } finally {
            g.dispose();
        }
        return image;
    }
}