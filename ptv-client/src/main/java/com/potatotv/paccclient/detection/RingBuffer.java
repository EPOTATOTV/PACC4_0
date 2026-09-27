package com.potatotv.paccclient.detection;

import java.util.ArrayList;
import java.util.List;

/**
 * 固定容量环形缓冲（检测热路径使用，不分配新数组）。
 * <p>非线程安全；跨线程访问请由调用方加锁（{@link BufferedInputSource} 已同步）。</p>
 *
 * @param <T> 元素类型
 */
public final class RingBuffer<T> {

    private final Object[] items;
    private int head;
    private int size;

    public RingBuffer(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("容量必须为正");
        this.items = new Object[capacity];
    }

    /** 追加元素；满时覆盖最旧元素。 */
    public void add(T value) {
        items[head] = value;
        head = (head + 1) % items.length;
        if (size < items.length) size++;
    }

    public int size() {
        return size;
    }

    public int capacity() {
        return items.length;
    }

    /** 全部元素，按写入顺序（旧→新）。 */
    @SuppressWarnings("unchecked")
    public List<T> snapshot() {
        List<T> out = new ArrayList<>(size);
        int start = (head - size + items.length) % items.length;
        for (int i = 0; i < size; i++) out.add((T) items[(start + i) % items.length]);
        return out;
    }

    /** 最近 n 个元素（旧→新）；n 超过容量时返回全部。 */
    public List<T> last(int n) {
        List<T> all = snapshot();
        if (n >= all.size()) return all;
        if (n <= 0) return List.of();
        return new ArrayList<>(all.subList(all.size() - n, all.size()));
    }

    public void clear() {
        for (int i = 0; i < items.length; i++) items[i] = null;
        head = 0;
        size = 0;
    }
}