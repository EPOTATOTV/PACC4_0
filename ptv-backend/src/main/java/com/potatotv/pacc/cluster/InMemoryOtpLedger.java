package com.potatotv.pacc.cluster;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 进程内验证码存储（单实例默认实现），get 时惰性清掉已过期条目。 */
public final class InMemoryOtpLedger implements OtpLedger {

    private final Map<String, OtpEntry> store = new ConcurrentHashMap<>();
    private final long ttlMs;

    public InMemoryOtpLedger(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    @Override
    public OtpEntry get(String key) {
        OtpEntry e = store.get(key);
        if (e != null && System.currentTimeMillis() - e.createdMs() > ttlMs) {
            store.remove(key);
            return null;
        }
        return e;
    }

    @Override
    public void put(String key, OtpEntry entry, long ttlMs) {
        store.put(key, entry);
    }

    @Override
    public void remove(String key) {
        store.remove(key);
    }
}