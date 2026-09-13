package com.potatotv.pacc.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 细粒度全局限流（P1）：按调用方身份（管理员 / API Key / 玩家）+ 请求类别（读 / 写 / 敏感）
 * 施加独立令牌桶，避免单账号/IP 限流被绕过后对管理端造成洪峰，也对读放大接口限速。
 * <p>与登录限流（{@link LoginThrottle}）叠加，二者维度不同：登录限流按“账号+IP”防爆破，
 * 本服务按“身份+接口类别”防整体洪峰。进程内实现，多实例可替换为 Redis 计数。</p>
 */
@Service
public class RateLimiterService {

    /** 桶容量，超过后进入丢弃窗口重回满桶。 */
    private record Bucket(double capacity, double perSecond) {}

    /** 不同请求类别的默认限速（perSecond, burst）。 */
    private static final Map<String, Bucket> CATEGORY_DEFAULTS = Map.of(
            "read", new Bucket(5.0, 60),
            "write", new Bucket(2.0, 30),
            "sensitive", new Bucket(2.0, 20));

    @Value("${pacc.ratelimit.enabled:true}")
    private boolean enabled;

    // discountedId -> bucket tokens
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** 令牌桶：固定速率补充 + 突发容量。 */
    private static final class TokenBucket {
        private final double capacity;
        private final double tokensPerSecond;
        private final AtomicLong nanos = new AtomicLong(System.nanoTime());
        private double tokens;

        TokenBucket(double capacity, double tokensPerSecond) {
            this.capacity = capacity;
            this.tokensPerSecond = tokensPerSecond;
            this.tokens = capacity;
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            long diff = now - nanos.get();
            nanos.set(now);
            tokens = Math.min(capacity, tokens + (diff / 1_000_000_000.0) * tokensPerSecond);
            if (tokens < 1.0) {
                return false;
            }
            tokens -= 1.0;
            return true;
        }
    }

    /** 请求是否放行。allowSensitive 命中 rate 类别的写/敏感操作。 */
    public boolean allow(String who, boolean write, boolean sensitive) {
        if (!enabled || who == null || who.isBlank()) {
            return true;
        }
        String category = sensitive ? "sensitive" : (write ? "write" : "read");
        Bucket cfg = CATEGORY_DEFAULTS.get(category);
        String key = category + ":" + who;
        TokenBucket tb = buckets.computeIfAbsent(key, k -> new TokenBucket(cfg.capacity(), cfg.perSecond()));
        return tb.tryAcquire();
    }
}