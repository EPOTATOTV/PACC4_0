package com.potatotv.paccclient.detection.stream;

/**
 * DF §4.1.1 滑动窗口：定容、O(1) 增删的近期信号与到达间隔统计。
 *
 * <p>窗口用环形数组保存最近 {@code capacity} 条事件的信号与相邻到达间隔，入窗时「加入新值、淘汰最旧值」
 * 各做常数次加减（运行和 / 平方和 / 间隔和），因此窗口维护是 O(1)/事件，不随窗口或历史长度增长。</p>
 *
 * <p>窗口提供的 {@link #ratePerSecond()} 与 {@link #burstScore()} 是快速规则层的输入：到达速率突变
 * 往往先于单点信号超限暴露连点器 / 脚本的节律特征。</p>
 */
public final class SlidingWindow {

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    private final double[] signals;
    private final long[] intervals;
    private final int mask;

    private int head;
    private int size;
    private double sumSignal;
    private double sumSignalSq;
    private long sumInterval;
    private long lastTimestamp;

    /** @param capacity 期望窗口长度（向上取整到 2 的幂，最小 2） */
    public SlidingWindow(int capacity) {
        int cap = 2;
        while (cap < capacity) {
            cap <<= 1;
        }
        this.mask = cap - 1;
        this.signals = new double[cap];
        this.intervals = new long[cap];
    }

    /** 追加一条事件（O(1)：加入新值 + 淘汰最旧值）。 */
    public void add(double signal, long timestampNanos) {
        if (size == signals.length) {
            int oldest = head;
            sumSignal -= signals[oldest];
            sumSignalSq -= signals[oldest] * signals[oldest];
            sumInterval -= intervals[oldest];
            head = (head + 1) & mask;
            size--;
        }
        int idx = (head + size) & mask;
        signals[idx] = signal;
        long interval = size == 0 ? 0L : (timestampNanos - lastTimestamp);
        intervals[idx] = interval;
        sumSignal += signal;
        sumSignalSq += signal * signal;
        sumInterval += interval;
        lastTimestamp = timestampNanos;
        size++;
    }

    /** 当前窗口内事件数。 */
    public int size() {
        return size;
    }

    /** 窗口内信号均值；空窗返回 0。 */
    public double mean() {
        return size == 0 ? 0.0 : sumSignal / size;
    }

    /** 窗口内信号总体标准差；空窗返回 0。 */
    public double std() {
        if (size == 0) {
            return 0.0;
        }
        double mean = sumSignal / size;
        double var = sumSignalSq / size - mean * mean;
        return var <= 0 ? 0.0 : Math.sqrt(var);
    }

    /** 平均到达间隔（毫秒）；样本不足返回 0。 */
    public double meanIntervalMs() {
        int n = intervalSamples();
        return n == 0 ? 0.0 : sumInterval / (double) n / 1_000_000.0;
    }

    /** 窗口内事件到达速率（事件/秒）；样本不足返回 0。 */
    public double ratePerSecond() {
        if (size < 2 || sumInterval <= 0) {
            return 0.0;
        }
        return intervalSamples() / (sumInterval / NANOS_PER_SECOND);
    }

    /**
     * 节律突变分 0-1：最近一次到达间隔相对窗口平均间隔的偏离程度。
     *
     * <p>以「最近间隔显著短于窗口均值」作为突发信号，值为 0 表示无突变。</p>
     */
    public double burstScore() {
        int n = intervalSamples();
        if (n < 2) {
            return 0.0;
        }
        double meanInterval = sumInterval / (double) n;
        if (meanInterval <= 0) {
            return 0.0;
        }
        long latest = intervals[(head + size - 1) & mask];
        double ratio = latest / meanInterval;
        return ratio >= 1.0 ? 0.0 : Math.min(1.0, (1.0 - ratio) * 2.0);
    }

    /** 参与间隔统计的样本数（窗口内第一条事件的间隔恒为 0，不计入）。 */
    private int intervalSamples() {
        return size <= 1 ? 0 : size - 1;
    }
}