package com.potatotv.paccclient.detection.stream;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * DF §4.1.1 固定内存的延迟直方图（HdrHistogram 式等宽分桶 + 溢出桶），用于统计流式检测各阶段耗时。
 *
 * <p>不引入第三方直方图依赖：用 {@link AtomicLongArray} 按「等宽分桶」计数，内存恒为
 * {@code bucketCount × 8} 字节，与观测次数无关；百分位取命中桶的<b>中点</b>，分辨率即桶宽
 * （默认 5µs），对「P95 &lt; 10ms」这类毫秒级门禁足够，且不会像上界口径那样把临界值误判为超标。</p>
 *
 * <p>超出量程的观测落入溢出桶，按量程上界计入（只影响极端长尾，不影响 P50/P95/P99）。</p>
 */
public final class LatencyHistogram {

    private static final double NANOS_PER_MS = 1_000_000.0;

    private final long bucketWidthNanos;
    private final int bucketCount;
    private final AtomicLongArray buckets;
    private final AtomicLong overflow = new AtomicLong();
    private final AtomicLong count = new AtomicLong();
    private final AtomicLong totalNanos = new AtomicLong();
    private final AtomicLong maxNanos = new AtomicLong();

    /**
     * @param maxNanos    量程上界（纳秒），超出者计入溢出桶
     * @param bucketCount 分桶数量（越多分辨率越高，内存线性增长）
     */
    public LatencyHistogram(long maxNanos, int bucketCount) {
        this.bucketCount = Math.max(1, bucketCount);
        this.bucketWidthNanos = Math.max(1L, maxNanos / this.bucketCount);
        this.buckets = new AtomicLongArray(this.bucketCount);
    }

    /** 记录一次耗时（纳秒；负值按 0 处理）。 */
    public void record(long nanos) {
        long v = Math.max(0L, nanos);
        count.incrementAndGet();
        totalNanos.addAndGet(v);
        maxNanos.accumulateAndGet(v, Math::max);
        int idx = (int) (v / bucketWidthNanos);
        if (idx >= bucketCount) {
            overflow.incrementAndGet();
        } else {
            buckets.incrementAndGet(idx);
        }
    }

    /** 观测总数。 */
    public long count() {
        return count.get();
    }

    /** 平均值（毫秒）。 */
    public double meanMs() {
        long n = count.get();
        return n == 0 ? 0.0 : totalNanos.get() / NANOS_PER_MS / n;
    }

    /** 最大值（毫秒）。 */
    public double maxMs() {
        return maxNanos.get() / NANOS_PER_MS;
    }

    /**
     * 百分位（毫秒，最近秩法，取命中桶中点）。
     *
     * @param p 百分位，取值 (0,1]；无观测时返回 0
     */
    public double percentileMs(double p) {
        long n = count.get();
        if (n == 0) {
            return 0.0;
        }
        double q = Math.max(0.0, Math.min(1.0, p));
        long target = Math.max(1L, (long) Math.ceil(q * n));
        long cumulative = 0;
        for (int i = 0; i < bucketCount; i++) {
            cumulative += buckets.get(i);
            if (cumulative >= target) {
                return (i * bucketWidthNanos + bucketWidthNanos / 2.0) / NANOS_PER_MS;
            }
        }
        return maxMs();
    }

    /** 溢出桶计数（量程之外的极端长尾观测数）。 */
    public long overflowCount() {
        return overflow.get();
    }

    /** 清空全部观测（仅用于测试的「预热后重新计数」观测面）。 */
    public void reset() {
        for (int i = 0; i < bucketCount; i++) {
            buckets.set(i, 0L);
        }
        overflow.set(0L);
        count.set(0L);
        totalNanos.set(0L);
        maxNanos.set(0L);
    }
}