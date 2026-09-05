package com.potatotv.paccclient.store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OfflineQueueTest {

    @TempDir Path dir;

    @Test
    void fifoAndDrain() {
        Path f = dir.resolve("q.enc");
        OfflineQueue q = new OfflineQueue(f, "pwd", 100, false);
        q.offer("a");
        q.offer("b");
        q.offer("c");
        assertEquals(3, q.pendingCount());
        assertEquals(List.of("a", "b", "c"), q.drain());
        assertEquals(0, q.pendingCount());
    }

    @Test
    void capacityEvictsOldest() {
        Path f = dir.resolve("q2.enc");
        OfflineQueue q = new OfflineQueue(f, "pwd", 2, false);
        q.offer("a");
        q.offer("b");
        q.offer("c");
        assertEquals(List.of("b", "c"), q.drain());
    }

    @Test
    void persistsAcrossInstances() {
        Path f = dir.resolve("q3.enc");
        OfflineQueue q1 = new OfflineQueue(f, "pwd", 100, false);
        q1.offer("x");
        q1.offer("y");
        OfflineQueue q2 = new OfflineQueue(f, "pwd", 100, true);
        assertEquals(List.of("x", "y"), q2.drain());
    }
}