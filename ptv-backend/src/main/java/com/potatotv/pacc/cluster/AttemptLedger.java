package com.potatotv.pacc.cluster;

/**
 * 频率限制的尝试记录存储抽象。默认进程内实现；多实例通过 {@code pacc.cluster.redis-enabled}
 * 切换为 Redis 集中式存储，语义保持一致（固定/滑动窗口，窗口内计数）。
 */
public interface AttemptLedger {

    /** 窗口内当前尝试计数（实现应顺带清掉过期记录）。 */
    int size(String key, long now);

    /** 记录一次尝试。 */
    void add(String key, long now);

    /** 清空该 key（登录成功后调用）。 */
    void clear(String key);
}