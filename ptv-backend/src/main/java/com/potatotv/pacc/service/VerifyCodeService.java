package com.potatotv.pacc.service;

import com.potatotv.pacc.cluster.InMemoryOtpLedger;
import com.potatotv.pacc.cluster.OtpEntry;
import com.potatotv.pacc.cluster.OtpLedger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 验证码（OTP）服务：带 TTL 存储，用于注册/重置前的邮箱/手机目标校验。
 * <p>存储经 {@link OtpLedger} 抽象，默认进程内；多实例时以 Redis 实现交换（见 cluster 包）。
 * 只存验证码哈希不存明文；错误 5 次锁定；过期自动清退。</p>
 */
@Component
public class VerifyCodeService {

    private static final int FAIL_LIMIT = 5;
    private static final SecureRandom RAND = new SecureRandom();

    private final long ttlMs;
    private final long resendMs;
    private final OtpLedger ledger;

    /** 测试便利构造：进程内实现，ttl=ttlMinutes 分钟、重发间隔=resendSeconds 秒。 */
    public VerifyCodeService(long ttlMinutes, long resendSeconds) {
        this(ttlMinutes, resendSeconds, new InMemoryOtpLedger(ttlMinutes * 60_000L));
    }

    /** Spring 主构造：注入存储实现，ttl/重发间隔来自配置。 */
    @Autowired
    public VerifyCodeService(@Value("${pacc.otp.ttl-minutes:10}") long ttlMinutes,
                             @Value("${pacc.otp.resend-seconds:60}") long resendSeconds,
                             OtpLedger ledger) {
        this.ttlMs = ttlMinutes * 60_000L;
        this.resendMs = resendSeconds * 1000L;
        this.ledger = ledger;
    }

    /**
     * 生成并保存验证码（按 scene:target 覆盖旧码）。
     *
     * @return 验证码明文；若与上次发出间隔不足 resendMs 则返回 null（调用方不重复发送）。
     */
    public String issue(String target, String scene) {
        String key = key(scene, target);
        long now = System.currentTimeMillis();
        OtpEntry prev = ledger.get(key);
        if (prev != null && now - prev.createdMs() < resendMs) {
            return null;
        }
        String code = String.format("%06d", RAND.nextInt(1_000_000));
        ledger.put(key, new OtpEntry(now, 0, 0, sha256Hex(code)), ttlMs);
        return code;
    }

    /** 校验并消费验证码（一次性）；错误 5 次锁定到过期。 */
    public boolean verify(String target, String scene, String code) {
        String key = key(scene, target);
        OtpEntry e = ledger.get(key);
        if (e == null || code == null) return false;
        long now = System.currentTimeMillis();
        if (now < e.failLockedMs()) return false;
        if (now - e.createdMs() > ttlMs) {
            ledger.remove(key);
            return false;
        }
        if (constantTimeEquals(e.codeHash(), sha256Hex(code))) {
            ledger.remove(key);
            return true;
        }
        int attempts = e.attempts() + 1;
        long locked = attempts >= FAIL_LIMIT ? now + ttlMs : e.failLockedMs();
        ledger.put(key, new OtpEntry(e.createdMs(), attempts, locked, e.codeHash()), ttlMs);
        return false;
    }

    private static String key(String scene, String target) {
        String s = scene == null ? "" : scene.trim().toLowerCase();
        return s + ":" + (target == null ? "" : target.trim().toLowerCase());
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