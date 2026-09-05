package com.potatotv.pacc.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验证码（OTP）服务：进程内带 TTL 存储，用于注册/重置前的邮箱/手机目标校验。
 * <p>进程内实现，适合单实例/演示；生产多实例应换用 Redis 等集中式存储（对齐 {@link LoginThrottle}）。
 * 只存验证码哈希不存明文；错误 5 次锁定；过期自动清退。</p>
 */
@Component
public class VerifyCodeService {

    private static final int FAIL_LIMIT = 5;
    private static final SecureRandom RAND = new SecureRandom();

    private final long ttlMs;
    private final long resendMs;

    /** 验证码条目：只存哈希与过期/锁定元数据。 */
    private record Entry(long createdMs, int attempts, long failLockedMs, String codeHash) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    public VerifyCodeService(@Value("${pacc.otp.ttl-minutes:10}") long ttlMinutes,
                             @Value("${pacc.otp.resend-seconds:60}") long resendSeconds) {
        this.ttlMs = ttlMinutes * 60_000L;
        this.resendMs = resendSeconds * 1000L;
    }

    /**
     * 生成并保存验证码（按 scene:target 覆盖旧码）。
     *
     * @return 验证码明文；若与上次发出间隔不足 resendMs 则返回 null（调用方不重复发送）。
     */
    public String issue(String target, String scene) {
        String key = key(scene, target);
        prune();
        long now = System.currentTimeMillis();
        Entry prev = store.get(key);
        if (prev != null && now - prev.createdMs() < resendMs) {
            return null;
        }
        String code = String.format("%06d", RAND.nextInt(1_000_000));
        store.put(key, new Entry(now, 0, 0, sha256Hex(code)));
        return code;
    }

    /** 校验并消费验证码（一次性）；错误 5 次锁定到过期。 */
    public boolean verify(String target, String scene, String code) {
        prune();
        String key = key(scene, target);
        Entry e = store.get(key);
        if (e == null || code == null) return false;
        long now = System.currentTimeMillis();
        if (now < e.failLockedMs()) return false;
        if (now - e.createdMs() > ttlMs) {
            store.remove(key);
            return false;
        }
        if (constantTimeEquals(e.codeHash, sha256Hex(code))) {
            store.remove(key);
            return true;
        }
        int attempts = e.attempts + 1;
        long locked = attempts >= FAIL_LIMIT ? now + ttlMs : e.failLockedMs;
        store.put(key, new Entry(e.createdMs, attempts, locked, e.codeHash));
        return false;
    }

    private static String key(String scene, String target) {
        return scene + ":" + (target == null ? "" : target.trim().toLowerCase());
    }

    private void prune() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(en -> now - en.getValue().createdMs > ttlMs);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) diff |= a.charAt(i) ^ b.charAt(i);
        return diff == 0;
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}