package com.potatotv.paccclient.redscreen;

import javax.swing.*;
import java.awt.*;

/**
 * 全屏红屏警告界面：全屏红色、置顶、永久保留（直至管理员查端解除）。
 * Swing AWT/S2 实现，独立于游戏窗口。
 */
public final class FullScreenRed {

    private static JFrame frame;

    /**
     * 展示红屏警告。level=2 二级 / level=3 三级（严重）。
     */
    public static void show(int level, String cheatType, String pteidMasked, String riskScore) {
        KeyboardLock.lock();
        SwingUtilities.invokeLater(() -> {
            if (frame != null) frame.dispose();
            frame = new JFrame("PACC 反作弊警告");
            frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setAlwaysOnTop(true);
            frame.setUndecorated(true);
            frame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            frame.setBackground(Color.RED);  // 置为红

            String headline = level >= 3 ? "严重作弊嫌疑" : "检测到可疑行为";
            String tip = "请保持现状等待管理员远程查端。输入已暂停，勿关闭本窗口。";

            JPanel panel = new JPanel(new GridBagLayout());
            panel.setBackground(new Color(0xE0, 0x00, 0x00));
            GridBagConstraints c = new GridBagConstraints();
            c.gridwidth = GridBagConstraints.REMAINDER;
            c.insets = new Insets(14, 24, 14, 24);

            JLabel mark = new JLabel("⚠ PACC 警告");
            mark.setFont(new Font(Font.DIALOG, Font.BOLD, 56));
            mark.setForeground(Color.WHITE);

            JLabel h = new JLabel("【" + headline + "】");
            h.setFont(new Font(Font.DIALOG, Font.BOLD, 30));
            h.setForeground(Color.WHITE);

            JLabel sub = new JLabel("<html>类型：" + cheatType + "　|　风险分：" + riskScore
                    + "　|　账号：" + pteidMasked + "</html>");
            sub.setFont(new Font(Font.DIALOG, Font.PLAIN, 20));
            sub.setForeground(Color.WHITE);

            JLabel tipLabel = new JLabel(tip);
            tipLabel.setFont(new Font(Font.DIALOG, Font.PLAIN, 16));
            tipLabel.setForeground(Color.WHITE);

            panel.add(mark, c);
            panel.add(h, c);
            panel.add(sub, c);
            panel.add(tipLabel, c);

            frame.setContentPane(panel);
            frame.setLocationByPlatform(true);
            frame.setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            frame.setVisible(true);
            frame.toFront();
            frame.repaint();
        });
    }

    /** 管理员查端解除后关闭红屏并恢复输入。 */
    public static void dismiss() {
        KeyboardLock.unlock();
        SwingUtilities.invokeLater(() -> {
            if (frame != null) {
                frame.dispose();
                frame = null;
            }
        });
    }
}