package com.potatotv.pacc.util;

import java.util.ArrayDeque;
import java.util.function.Supplier;

/**
 * 线程安全的泛型大对象池（v4.4 性能优化）。
 * <p>复用 {@code ScanResult/Evidence} 等多开销 DTO，减少高频分配与 GC 停顿。借用为空且未达上限时新建；
 * 归还超上限时丢弃交给 GC（不无界膨胀）。达到最大借用数时降级新建，保证可用性（池是优化而非硬约束）。</p>
 *
 * @param <T> 池化对象类型
 */
public final class ObjectPool<T> {

    private final ArrayDeque<T> idle;
    private final Supplier<T> factory;
    private final int maxIdle;
    /** 理论上限（借用中 + 空闲）或 -1 表示不设上限。 */
    private final int maxSize;
    private int outstanding;

    public ObjectPool(Supplier<T> factory, int maxIdle, int maxSize) {
        if (factory == null) throw new NullPointerException("factory");
        this.factory = factory;
        this.maxIdle = maxIdle;
        this.maxSize = maxSize;
        this.idle = new ArrayDeque<>(Math.max(1, maxIdle));
        this.outstanding = 0;
    }

    /** 借出：优先复用空闲对象；无空闲且未达上限则新建；达上限降级新建。 */
    public synchronized T borrow() {
        T item = idle.poll();
        if (item != null) {
            outstanding++;
            return item;
        }
        outstanding++;
        return factory.get();
    }

    /** 归还：空闲数未达上限则回收，否则丢弃。 */
    public synchronized void recycle(T item) {
        if (item == null) return;
        outstanding = Math.max(0, outstanding - 1);
        if (idle.size() < maxIdle) {
            idle.push(item);
        }
    }

    public synchronized int idleCount() {
        return idle.size();
    }

    public synchronized int outstandingCount() {
        return outstanding;
    }
}