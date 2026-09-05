package com.potatotv.pacc.cluster;

/**
 * 验证码存储抽象。默认进程内；多实例切换 Redis 集中式存储（带过期清理），
 * 语义与单实例一致（TTL、错误锁定、一次性消费）。
 */
public interface OtpLedger {

    OtpEntry get(String key);

    void put(String key, OtpEntry entry, long ttlMs);

    void remove(String key);
}