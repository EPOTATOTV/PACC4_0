package com.potatotv.paccclient.apm;

import java.util.Arrays;

/**
 * 游戏性能维度指标采集。
 *
 * <p>本 Java 客户端与游戏进程并列运行，不在游戏内部，因此只能采信「由检测循环喂入」的帧时序样本，
 * 任何需要 hook 外部进程（GPU 帧时间、渲染队列）的指标本模块都无法产出——不产出而不是填 0。</p>
 *
 * <p>热路径零分配：帧边界写入自实现的 {@code long[]} 定长窗口（4096），不用
 * {@link com.potatotv.paccclient.detection.RingBuffer}，后者存 {@code Long} 会在每次写入时装箱，
 * 每秒上万帧的调用量下装箱压力不可接受。窗口满时覆盖最旧样本，语义等同「最近 4096 帧」。</p>
 *
 * <p>{@link #frame(long)} 由渲染/检测循环在每帧边界调用；{@link #collect(ApmSnapshot)} 由 APM
 * 采样线程每秒调用一次，计算完成后立即清空窗口，使指标只描述「刚过去的一秒」。</p>
 */
public final class GameMetrics {

    private static final int CAPACITY = 4096;
    /** 帧时超过该毫秒数计为一次卡顿。 */
    private static final double STUTTER_MS = 50.0;

    private final long[] frames = new long[CAPACITY];
    private int frameHead;
    private int frameSize;
    private final long[] inputs = new long[CAPACITY];
    private int inputHead;
    private int inputSize;

    /** 记录一次帧边界（{@code System.nanoTime()} 时间戳）。 */
    public synchronized void frame(long nanos) {
        frames[frameHead] = nanos;
        frameHead = (frameHead + 1) % CAPACITY;
        if (frameSize < CAPACITY) frameSize++;
    }

    /** 记录一次按键→上屏延迟样本（纳秒）。当前无对应上报字段，仅保留供本地诊断。 */
    public synchronized void input(long nanos) {
        inputs[inputHead] = Math.max(0, nanos);
        inputHead = (inputHead + 1) % CAPACITY;
        if (inputSize < CAPACITY) inputSize++;
    }

    /** 已采样的按键→上屏平均延迟（毫秒）；无样本返回 0。 */
    public synchronized double meanInputLatencyMs() {
        if (inputSize == 0) return 0.0;
        long sum = 0;
        int start = (inputHead - inputSize + CAPACITY) % CAPACITY;
        for (int i = 0; i < inputSize; i++) sum += inputs[(start + i) % CAPACITY];
        return sum / (double) inputSize / 1_000_000.0;
    }

    /** 计算上一秒的帧时序指标并清空窗口；样本不足两帧时不产出任何指标。 */
    public synchronized void collect(ApmSnapshot snapshot) {
        int n = frameSize;
        if (n < 2) {
            clearWindows();
            return;
        }
        int start = (frameHead - frameSize + CAPACITY) % CAPACITY;
        double[] deltas = new double[n - 1];
        int valid = 0;
        for (int i = 0; i < n - 1; i++) {
            long a = frames[(start + i) % CAPACITY];
            long b = frames[(start + i + 1) % CAPACITY];
            long d = b - a;
            // 非单调时间戳（时钟跳变/乱序喂入）丢弃，避免把异常值算进分位数
            if (d > 0) deltas[valid++] = d / 1_000_000.0;
        }
        clearWindows();
        if (valid == 0) return;

        double[] sorted = Arrays.copyOf(deltas, valid);
        Arrays.sort(sorted);

        double sum = 0;
        int stutter = 0;
        for (int i = 0; i < valid; i++) {
            sum += sorted[i];
            if (sorted[i] > STUTTER_MS) stutter++;
        }
        double mean = sum / valid;
        double variance = 0;
        for (int i = 0; i < valid; i++) {
            double diff = sorted[i] - mean;
            variance += diff * diff;
        }
        variance /= valid;

        snapshot.with("game_frame_time_mean", mean);
        snapshot.with("game_frame_time_p95", percentile(sorted, 0.95));
        snapshot.with("game_frame_time_p99", percentile(sorted, 0.99));
        snapshot.with("game_frame_time_var", variance);
        snapshot.with("game_frame_stutter", stutter);
        if (mean > 0) snapshot.with("game_fps", 1000.0 / mean);
    }

    /** 最近一帧的时间戳（无样本返回 0），供诊断。 */
    public synchronized long lastFrameNanos() {
        return frameSize == 0 ? 0 : frames[(frameHead - 1 + CAPACITY) % CAPACITY];
    }

    private void clearWindows() {
        frameHead = 0;
        frameSize = 0;
        inputHead = 0;
        inputSize = 0;
    }

    /** 已排序数组的 q 分位（最近秩法），越界自动夹取。 */
    private static double percentile(double[] sorted, double q) {
        if (sorted.length == 0) return 0.0;
        int idx = (int) Math.ceil(q * sorted.length) - 1;
        if (idx < 0) idx = 0;
        if (idx >= sorted.length) idx = sorted.length - 1;
        return sorted[idx];
    }
}