package com.potatotv.pacc.cluster;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内滑动窗口频率限制（单实例默认实现）。
 * <p>所有对队列的读写都经由 {@link ConcurrentHashMap} 的 compute 系列方法完成：
 * 同一个 key 的 compute 由所在桶的锁串行化，因此「读计数 + 清理过期」与
 * 「清理 + 追加」之间不会交错。早期实现用 synchronized(队列) 保护单个队列，
 * 却无法阻止另一线程把空队列从 map 中摘除，导致新记录写进已脱离 map 的队列后丢失。</p>
 */
public final class InMemoryAttemptLedger implements AttemptLedger {

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final long windowMs;

    public InMemoryAttemptLedger(long windowMs) {
        this.windowMs = windowMs;
    }

    @Override
    public int size(String key, long now) {
        int[] count = {0};
        attempts.computeIfPresent(key, (k, q) -> {
            prune(q, now);
            count[0] = q.size();
            // 队列清空后一并摘除，避免被扫过的 key 长期占用内存
            return q.isEmpty() ? null : q;
        });
        return count[0];
    }

    @Override
    public void add(String key, long now) {
        attempts.compute(key, (k, q) -> {
            Deque<Long> d = (q == null) ? new ArrayDeque<>() : q;
            prune(d, now);
            d.addLast(now);
            return d;
        });
    }

    @Override
    public void clear(String key) {
        attempts.remove(key);
    }

    /** 丢弃已滑出窗口的记录。 */
    private void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.removeFirst();
        }
    }
}
