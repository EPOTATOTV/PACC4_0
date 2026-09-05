package com.potatotv.pacc.cluster;

/**
 * Redis 集中式滑动窗口频率限制。用 ZSET 以时间戳为 score、唯一成员为元素，
 * 跨实例共享同一窗口计数，供多实例限流去重。
 */
public final class RedisAttemptLedger implements AttemptLedger {

    private final RedisClient redis;
    private final long windowMs;

    public RedisAttemptLedger(RedisClient redis, long windowMs) {
        this.redis = redis;
        this.windowMs = windowMs;
    }

    @Override
    public int size(String key, long now) {
        prune(key, now);
        Object n = redis.execute("ZCARD", key);
        return n instanceof Long l ? l.intValue() : 0;
    }

    @Override
    public void add(String key, long now) {
        prune(key, now);
        String member = now + "-" + Long.toHexString(System.nanoTime());
        redis.execute("ZADD", key, String.valueOf(now), member);
    }

    @Override
    public void clear(String key) {
        redis.execute("DEL", key);
    }

    private void prune(String key, long now) {
        long min = now - windowMs;
        redis.execute("ZREMRANGEBYSCORE", key, "0", String.valueOf(min));
    }
}