package com.potatotv.pacc.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 大对象池确定性单测：借用/归还复用、上限丢弃、降级新建、并发安全。
 */
class ObjectPoolTest {

    @Test
    void borrowCreatesAndRecycleReuses() {
        ObjectPool<StringBuilder> pool = new ObjectPool<>(() -> new StringBuilder("x"), 5, 1000);
        StringBuilder a = pool.borrow();
        assertNotNull(a);
        pool.recycle(a);
        assertEquals(1, pool.idleCount());
        StringBuilder b = pool.borrow();
        assertSame(a, b, "归还后应复用同一对象");
    }

    @Test
    void recyclesUpToMaxIdleThenDrops() {
        AtomicInteger created = new AtomicInteger();
        ObjectPool<Object> pool = new ObjectPool<>(() -> { created.getAndIncrement(); return new Object(); }, 2, 100);
        Object a = pool.borrow();
        Object b = pool.borrow();
        Object c = pool.borrow();
        pool.recycle(a);
        pool.recycle(b);
        pool.recycle(c); // 第 3 个超出 maxIdle=2，丢弃
        assertEquals(2, pool.idleCount());
    }

    @Test
    void degradesGracefullyWhenEmpty() {
        ObjectPool<Object> pool = new ObjectPool<>(Object::new, 1, 1);
        Object a = pool.borrow();
        // 已借 1（达 maxSize=1），再次借用降级新建而非阻塞
        Object b = pool.borrow();
        assertNotNull(a);
        assertNotNull(b);
    }

    @Test
    void recycleNullIsNoop() {
        ObjectPool<Object> pool = new ObjectPool<>(Object::new, 2, 100);
        pool.recycle(null);
        assertEquals(0, pool.idleCount());
    }

    @Test
    void rejectsNullFactory() {
        assertThrows(NullPointerException.class, () -> new ObjectPool<>(null, 1, 100));
    }

    @Test
    void concurrentBorrowRecycleStaysWithinIdleCap() throws Exception {
        ObjectPool<Object> pool = new ObjectPool<>(Object::new, 10, 10_000);
        Thread[] ts = new Thread[8];
        for (int i = 0; i < ts.length; i++) ts[i] = new Thread(() -> {
            for (int j = 0; j < 1000; j++) pool.recycle(pool.borrow());
        });
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        assertEquals(true, pool.idleCount() <= 10);
        assertEquals(0, pool.outstandingCount());
    }
}