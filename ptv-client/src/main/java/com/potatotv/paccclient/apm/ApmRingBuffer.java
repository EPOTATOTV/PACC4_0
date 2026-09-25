package com.potatotv.paccclient.apm;

import java.util.ArrayList;
import java.util.List;

/**
 * APM 快照环形缓冲（覆盖最旧语义）。
 *
 * <p>与 {@code detection.RingBuffer} 的区别：这里存的是对象快照且被多个线程访问——采样线程写、
 * 定时 flush 线程读——因此用最简单的监视器锁保证可见性；吞吐上限只有 1 写/秒 + 1 读/5 分钟，
 * 加锁的代价可以忽略，换成无锁结构反而更容易出隐蔽 bug。</p>
 *
 * <p>固定容量即「离线兜底上限」：长期无法上报时新快照覆盖最旧，缓冲永远不超过 7 天容量，杜绝
 * 永久离线导致的内存增长。</p>
 */
final class ApmRingBuffer {

    private final ApmSnapshot[] items;
    private int head;
    private int size;

    ApmRingBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("容量必须为正");
        this.items = new ApmSnapshot[capacity];
    }

    /** 追加快照；满时覆盖最旧。 */
    synchronized void add(ApmSnapshot snapshot) {
        items[head] = snapshot;
        head = (head + 1) % items.length;
        if (size < items.length) size++;
    }

    synchronized int size() {
        return size;
    }

    /** 只看最旧的至多 max 条，不移除——成功上报后才调用 {@link #removeOldest(int)}。 */
    synchronized List<ApmSnapshot> peekOldest(int max) {
        int n = Math.min(max, size);
        List<ApmSnapshot> out = new ArrayList<>(n);
        int start = (head - size + items.length) % items.length;
        for (int i = 0; i < n; i++) {
            out.add(items[(start + i) % items.length]);
        }
        return out;
    }

    /** 移除最旧的 n 条（上报成功后调用）。 */
    synchronized void removeOldest(int n) {
        for (int i = 0; i < n && size > 0; i++) {
            int idx = (head - size + items.length) % items.length;
            items[idx] = null;
            size--;
        }
    }
}