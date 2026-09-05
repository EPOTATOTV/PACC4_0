package com.potatotv.pacc.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 线程安全的 LRU 特征缓存（v4.4 性能优化）。
 * <p>缓存重复计算/扫描的特征值，命中后不再重复计算；插入访问有序按访问顺序淘汰 LRU 元素。</p>
 *
 * @param <K> 特征键
 * @param <V> 特征值
 */
public final class FeatureCache<K, V> {

    private static final float LOAD_FACTOR = 0.75f;

    private final int capacity;
    private final LinkedHashMap<K, V> map;

    public FeatureCache(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be > 0");
        this.capacity = capacity;
        this.map = new LinkedHashMap<>(Math.max(16, capacity), LOAD_FACTOR, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > FeatureCache.this.capacity;
            }
        };
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    /** 命中则返回，未命中则经 loader 计算并缓存。 */
    public synchronized V computeIfAbsent(K key, java.util.function.Function<? super K, ? extends V> loader) {
        return map.computeIfAbsent(key, loader);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    public synchronized void remove(K key) {
        map.remove(key);
    }

    public synchronized void clear() {
        map.clear();
    }

    public synchronized int size() {
        return map.size();
    }

    public int capacity() {
        return capacity;
    }
}