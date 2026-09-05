package com.potatotv.pacc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 特征 LRU 缓存确定性单测：命中/未命中、LRU 淘汰、并发安全。
 */
class FeatureCacheTest {

    @Test
    void computeIfAbsentCachesValue() {
        FeatureCache<String, Integer> cache = new FeatureCache<>(4);
        AtomicInteger loads = new AtomicInteger();
        assertEquals(1, cache.computeIfAbsent("a", k -> loads.incrementAndGet()));
        assertEquals(1, cache.computeIfAbsent("a", k -> loads.incrementAndGet()));
        assertEquals(1, loads.get(), "命中应复用缓存，不重复计算");
    }

    @Test
    void evictsLeastRecentlyUsed() {
        FeatureCache<String, Integer> cache = new FeatureCache<>(2);
        cache.put("a", 1);
        cache.put("b", 2);
        assertEquals(Integer.valueOf(1), cache.get("a")); // 访问 a → b 成为 LRU
        cache.put("c", 3);                                // 淘汰 b
        assertEquals(Integer.valueOf(1), cache.get("a"));
        assertNull(cache.get("b"));
        assertEquals(Integer.valueOf(3), cache.get("c"));
    }

    @Test
    void removeAndClear() {
        FeatureCache<String, Integer> cache = new FeatureCache<>(3);
        cache.put("a", 1);
        cache.remove("a");
        assertNull(cache.get("a"));
        cache.put("b", 2);
        cache.clear();
        assertEquals(0, cache.size());
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new FeatureCache<>(0));
    }

    @Test
    void concurrentAccessDoesNotLoseEntries() throws Exception {
        FeatureCache<Integer, Integer> cache = new FeatureCache<>(100_000);
        int threads = 8, perThread = 500;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int base = i * 100_000;
            ts[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) cache.put(base + j, j);
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();
        assertEquals(threads * perThread, cache.size());
    }
}