package com.potatotv.pacc.cluster;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内滑动窗口频率限制（单实例默认实现）。 */
public final class InMemoryAttemptLedger implements AttemptLedger {

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final long windowMs;

    public InMemoryAttemptLedger(long windowMs) {
        this.windowMs = windowMs;
    }

    @Override
    public int size(String key, long now) {
        Deque<Long> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, key, now);
            return q.size();
        }
    }

    @Override
    public void add(String key, long now) {
        Deque<Long> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, key, now);
            q.addLast(now);
        }
    }

    @Override
    public void clear(String key) {
        attempts.remove(key);
    }

    private void prune(Deque<Long> q, String key, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > windowMs) {
            q.removeFirst();
        }
        if (q.isEmpty()) {
            attempts.values().remove(q);
        }
    }
}