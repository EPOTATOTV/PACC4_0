package com.potatotv.paccclient.detection.input;

/**
 * 板载宏多维打分（文档 §3.5）。
 *
 * <p>单一指标不触发：设备 +15、宏软件 +10、固定间隔 +30、爆发 +25、抖动 &lt;1.0ms +20、
 * 键鼠同步 &gt;0.8 +20，cap 100。只有多维印证才可能达到阈值，避免竞技玩家误报。</p>
 */
public final class OnboardMacroDetector {

    private static final int DEVICE_WEIGHT = 15;
    private static final int SOFTWARE_WEIGHT = 10;
    private static final int FIXED_WEIGHT = 30;
    private static final int BURST_WEIGHT = 25;
    private static final int JITTER_WEIGHT = 20;
    private static final int SYNC_WEIGHT = 20;
    private static final double JITTER_THRESHOLD_MS = 1.0;
    private static final double SYNC_THRESHOLD = 0.8;

    private OnboardMacroDetector() {
    }

    /**
     * 综合打分。
     *
     * @param macroCapableDevice   存在板载宏可编程设备
     * @param macroSoftwareRunning 宏软件在运行
     * @param fixedInterval        固定间隔点击
     * @param burstPattern         爆发点击模式
     * @param jitterMs             点击抖动（ms）；&lt;0 表示无数据不计分
     * @param keyClickSync         键鼠同步率
     * @return 0-100
     */
    public static int score(boolean macroCapableDevice, boolean macroSoftwareRunning,
                            boolean fixedInterval, boolean burstPattern,
                            double jitterMs, double keyClickSync) {
        int score = 0;
        if (macroCapableDevice) {
            score += DEVICE_WEIGHT;
        }
        if (macroSoftwareRunning) {
            score += SOFTWARE_WEIGHT;
        }
        if (fixedInterval) {
            score += FIXED_WEIGHT;
        }
        if (burstPattern) {
            score += BURST_WEIGHT;
        }
        if (jitterMs >= 0 && jitterMs < JITTER_THRESHOLD_MS) {
            score += JITTER_WEIGHT;
        }
        if (keyClickSync > SYNC_THRESHOLD) {
            score += SYNC_WEIGHT;
        }
        return Math.min(100, score);
    }
}