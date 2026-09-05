package com.potatotv.pacc.cluster;

import java.util.List;
import java.util.Map;

/**
 * Redis 集中式验证码存储。以哈希字段保存条目（created/attempts/locked/hash），
 * 写入时带过期，交给 Redis 自动清退；读操作不缓存于本机，跨实例一致。
 */
public final class RedisOtpLedger implements OtpLedger {

    private final RedisClient redis;

    public RedisOtpLedger(RedisClient redis) {
        this.redis = redis;
    }

    @Override
    public OtpEntry get(String key) {
        Object r = redis.execute("HGETALL", key);
        if (!(r instanceof List<?> list) || list.isEmpty()) return null;
        Map<String, String> fields = new java.util.HashMap<>();
        for (int i = 0; i + 1 < list.size(); i += 2) {
            fields.put(String.valueOf(list.get(i)), String.valueOf(list.get(i + 1)));
        }
        long created = Long.parseLong(fields.getOrDefault("created", "0"));
        int attempts = Integer.parseInt(fields.getOrDefault("attempts", "0"));
        long locked = Long.parseLong(fields.getOrDefault("locked", "0"));
        String hash = fields.getOrDefault("hash", "");
        return new OtpEntry(created, attempts, locked, hash);
    }

    @Override
    public void put(String key, OtpEntry entry, long ttlMs) {
        redis.execute("HSET", key, "created", String.valueOf(entry.createdMs()),
                "attempts", String.valueOf(entry.attempts()),
                "locked", String.valueOf(entry.failLockedMs()),
                "hash", entry.codeHash());
        redis.execute("EXPIRE", key, String.valueOf(Math.max(1, ttlMs / 1000)));
    }

    @Override
    public void remove(String key) {
        redis.execute("DEL", key);
    }
}