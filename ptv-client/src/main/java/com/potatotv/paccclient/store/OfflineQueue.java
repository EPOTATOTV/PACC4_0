package com.potatotv.paccclient.store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 离线上报 FIFO 队列（持久化到本地加密文件）。
 * <p>服务器不可达或发送失败时暂存待上报事件（已签名编码串），通道恢复后批量补报；
 * 超容量丢弃最旧，防止无限增长。进程重启后从磁盘恢复未补报条目。</p>
 */
public final class OfflineQueue {

    private final Path file;
    private final String password;
    private final int capacity;
    private final ArrayDeque<String> entries = new ArrayDeque<>();

    public OfflineQueue(Path file, String password, int capacity, boolean loadExisting) {
        this.file = file;
        this.password = password;
        this.capacity = Math.max(1, capacity);
        if (loadExisting) {
            try {
                byte[] blob = LocalSecureStore.load(file, password);
                entries.addAll(LocalSecureStore.decodeStrings(blob));
                if (!entries.isEmpty()) {
                    System.out.println("[PTV-Client] 离线队列载入 " + entries.size() + " 条待补报");
                }
            } catch (IOException e) {
                System.err.println("[PTV-Client] 离线队列读取失败，重置为空: " + e.getMessage());
            }
        }
    }

    public synchronized int pendingCount() {
        return entries.size();
    }

    /** 入队并持久化；超容量丢弃最旧。 */
    public synchronized void offer(String encoded) {
        if (entries.size() >= capacity) entries.removeFirst();
        entries.addLast(encoded);
        persist();
    }

    /** 取出全部并清空（调用方在发送失败时需重新 offer）。 */
    public synchronized List<String> drain() {
        List<String> out = new ArrayList<>(entries);
        entries.clear();
        persist();
        return out;
    }

    private void persist() {
        try {
            LocalSecureStore.save(file, password, LocalSecureStore.encodeStrings(new ArrayList<>(entries)));
        } catch (IOException e) {
            System.err.println("[PTV-Client] 离线队列持久化失败: " + e.getMessage());
        }
    }
}