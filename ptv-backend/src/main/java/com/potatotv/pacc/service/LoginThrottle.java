package com.potatotv.pacc.service;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录频率限制（进程内固定窗口）：对同作用域（账号 / 客户端 IP）在窗口内
 * 超过阈值即拒绝，缓解管理后台 / 玩家登录的暴力破解。
 * <p>进程内实现，适合单实例或演示；生产多实例应换用 Redis 等集中式限流。</p>
 */
@Component
public class LoginThrottle {

    private static final long WINDOW_MS = 60_000L;
    private static final int MAX_ATTEMPTS = 5;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();

    /** 当前窗口内是否允许继续尝试。 */
    public boolean allowed(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            return q.size() < MAX_ATTEMPTS;
        }
    }

    /** 记录一次失败尝试。 */
    public void hit(String key) {
        long now = System.currentTimeMillis();
        Deque<Long> q = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            q.addLast(now);
        }
    }

    /** 登录成功后清除计数，避免用户被历史失败误伤。 */
    public void clear(String key) {
        attempts.remove(key);
    }

    private void prune(Deque<Long> q, long now) {
        while (!q.isEmpty() && now - q.peekFirst() > WINDOW_MS) {
            q.removeFirst();
        }
        if (q.isEmpty()) {
            attempts.values().remove(q);
        }
    }
}