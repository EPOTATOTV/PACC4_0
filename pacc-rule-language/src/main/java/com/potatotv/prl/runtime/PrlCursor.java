package com.potatotv.prl.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * {@code for} 循环的迭代游标（{@code IrOp.GET_ITER}/{@code IrOp.ITER_NEXT} 的运行时表示）。
 *
 * <p>取的是快照而不是视图：规则体里改集合（{@code list[i] = x}）不该让正在进行的迭代跳到别处，
 * 也不该抛 {@code ConcurrentModificationException} —— 沙箱保证规则不会影响宿主，那么迭代中途
 * 改集合的后果也不该反过来影响规则自身。</p>
 *
 * <p>迭代顺序：{@code list}/{@code tuple} 按位置，{@code set} 按插入顺序（{@code LinkedHashSet}），
 * {@code map} 按键的插入顺序（§2.3.2 的 {@code map} 用 {@code LinkedHashMap}），
 * {@code string} 逐字符。</p>
 */
public final class PrlCursor {

    private final List<Object> items;
    private int index;

    private PrlCursor(List<Object> items) {
        this.items = items;
    }

    public static PrlCursor of(Object source) {
        if (source instanceof String text) {
            List<Object> chars = new ArrayList<>(text.length());
            for (int i = 0; i < text.length(); i++) {
                chars.add(String.valueOf(text.charAt(i)));
            }
            return new PrlCursor(chars);
        }
        if (source instanceof Map<?, ?> map) {
            return new PrlCursor(new ArrayList<>(map.keySet()));
        }
        if (source instanceof Iterable<?> iterable) {
            List<Object> snapshot = new ArrayList<>();
            for (Object element : iterable) {
                snapshot.add(element);
            }
            return new PrlCursor(snapshot);
        }
        throw new PrlExecutionException("for 循环需要集合，实际为 " + PrlValues.describe(source));
    }

    public boolean hasNext() {
        return index < items.size();
    }

    public Object next() {
        return items.get(index++);
    }
}