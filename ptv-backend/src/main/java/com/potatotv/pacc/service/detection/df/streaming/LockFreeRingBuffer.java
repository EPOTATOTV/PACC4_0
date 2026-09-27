package com.potatotv.pacc.service.detection.df.streaming;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DF §4.1.1 无锁单生产者/单消费者环形缓冲：定容、零分配、无阻塞。
 *
 * <p>容量向上取整到 2 的幂，用掩码代替取模；写入位点与读取位点各由一个 {@link AtomicLong} 维护，
 * 生产者在写满时不再自旋等待，而是直接丢弃并累加 {@link #drops()}——检测链路宁可丢样本也不能阻塞
 * 输入事件流。生产者位点用 {@code lazySet}（release 语义）发布，消费者读取 {@code tail} 后即可见到
 * 槽位内容，因此不需要额外锁。</p>
 *
 * <p><b>并发约定</b>：本实现面向「单生产者 + 单消费者」；多生产者场景由调用方
 * （{@link StreamingDetectionEngine#ingest}）在外层串行化，从而保持内部完全无锁。</p>
 *
 * @param <T> 槽位元素类型
 */
public final class LockFreeRingBuffer<T> {

    private final Object[] slots;
    private final int mask;
    private final AtomicLong head = new AtomicLong();
    private final AtomicLong tail = new AtomicLong();
    private final AtomicLong drops = new AtomicLong();

    /** 按期望容量构造（自动向上取整到 2 的幂，最小 2）。 */
    public LockFreeRingBuffer(int requestedCapacity) {
        int capacity = 2;
        while (capacity < requestedCapacity) {
            capacity <<= 1;
        }
        this.slots = new Object[capacity];
        this.mask = capacity - 1;
    }

    /**
     * 尝试写入一个元素。
     *
     * @return 写入成功返回 true；缓冲已满时丢弃返回 false（不阻塞、不自旋）
     */
    public boolean offer(T item) {
        long t = tail.get();
        if (t - head.get() >= slots.length) {
            drops.incrementAndGet();
            return false;
        }
        slots[(int) (t & mask)] = item;
        tail.lazySet(t + 1);
        return true;
    }

    /** 取出下一个元素；缓冲为空返回 null。 */
    @SuppressWarnings("unchecked")
    public T poll() {
        long h = head.get();
        if (h >= tail.get()) {
            return null;
        }
        int idx = (int) (h & mask);
        T item = (T) slots[idx];
        slots[idx] = null;
        head.set(h + 1);
        return item;
    }

    /** 当前在缓冲中的元素个数（近似值，供指标展示）。 */
    public int size() {
        long n = tail.get() - head.get();
        return n <= 0 ? 0 : (int) Math.min(n, slots.length);
    }

    /** 因缓冲已满被丢弃的元素累计数。 */
    public long drops() {
        return drops.get();
    }

    /** 缓冲容量。 */
    public int capacity() {
        return slots.length;
    }
}