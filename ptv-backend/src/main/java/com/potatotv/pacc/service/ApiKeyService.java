package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ApiKey;
import com.potatotv.pacc.repository.ApiKeyRepository;
import com.potatotv.pacc.repository.ApiUsageLogRepository;
import com.potatotv.pacc.util.ApiCrypto;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;

/**
 * 开放 API 密钥管理（v4.8）：创建/轮换/禁用/删除、限流判定、调用审计落库。
 * <p>密钥明文仅在新建/轮换时返回一次；落库为平台主密钥加密密文。</p>
 */
@Service
@RequiredArgsConstructor
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final ApiKeyRepository keyRepository;
    private final ApiUsageLogRepository usageLogRepository;

    @Value("${pacc.security.api-master-secret:pacc-dev-api-master-key-change-me}")
    private String masterSecret;

    /** 新建密钥，仅此一次返回明文 secret。 */
    @Transactional
    public CreatedKey create(String name, String tenantId, String plan, String scopes,
                             String categories, String ipWhitelist, Integer rateLimit,
                             String webhookUrl, String webhookSecret, String createdBy) {
        String keyId = "kpt_" + hex(RANDOM, 8);
        String secret = hex(RANDOM, 16);
        int limit = rateLimit != null && rateLimit > 0 ? rateLimit : defaultLimit(plan);
        ApiKey k = ApiKey.builder()
                .keyId(keyId)
                .name(name == null || name.isBlank() ? "未命名密钥" : name)
                .tenantId(tenantId == null || tenantId.isBlank() ? "platform" : tenantId)
                .plan(plan == null || plan.isBlank() ? "PRO" : plan.toUpperCase())
                .secretEnc(ApiCrypto.encrypt(secret, masterSecret))
                .scopes(scopes == null || scopes.isBlank() ? "READ" : scopes)
                .categories(categories)
                .ipWhitelist(ipWhitelist)
                .rateLimitPerHour(limit)
                .webhookUrl(webhookUrl)
                .webhookSecret(webhookSecret)
                .enabled(true)
                .createdBy(createdBy)
                .build();
        keyRepository.save(k);
        return new CreatedKey(k, secret);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> list(String tenantId) {
        return keyRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public ApiKey get(String keyId) {
        return keyRepository.findFirstByKeyId(keyId).orElse(null);
    }

    /** 轮换：返回新明文 secret；密钥失效即时生效。 */
    @Transactional
    public CreatedKey rotate(String keyId) {
        ApiKey k = keyRepository.findFirstByKeyId(keyId).orElseThrow(
                () -> new IllegalArgumentException("密钥不存在"));
        String secret = hex(RANDOM, 16);
        k.setSecretEnc(ApiCrypto.encrypt(secret, masterSecret));
        k.setLastUsedAt(Instant.now());
        keyRepository.save(k);
        return new CreatedKey(k, secret);
    }

    @Transactional
    public void setEnabled(String keyId, boolean enabled) {
        ApiKey k = keyRepository.findFirstByKeyId(keyId).orElseThrow(
                () -> new IllegalArgumentException("密钥不存在"));
        k.setEnabled(enabled);
        keyRepository.save(k);
    }

    @Transactional
    public void delete(String keyId) {
        keyRepository.findFirstByKeyId(keyId).ifPresent(keyRepository::delete);
    }

    @Transactional
    public void updateWebhook(String keyId, String url, String webhookSecret) {
        ApiKey k = keyRepository.findFirstByKeyId(keyId).orElseThrow(
                () -> new IllegalArgumentException("密钥不存在"));
        k.setWebhookUrl(url == null || url.isBlank() ? null : url);
        k.setWebhookSecret(webhookSecret == null || webhookSecret.isBlank() ? null : webhookSecret);
        keyRepository.save(k);
    }

    /** 该密钥近一小时调用数是否已达上限（429）。 */
    public boolean rateExceeded(ApiKey key) {
        long used = usageLogRepository.countByApiKeyIdAndCreatedAtAfter(key.getKeyId(), Instant.now().minusSeconds(3600));
        return used >= (long) Math.max(1, key.getRateLimitPerHour());
    }

    public static int defaultLimit(String plan) {
        if (plan == null) return 1000;
        return switch (plan.toUpperCase()) {
            case "FREE" -> 100;
            case "ENTERPRISE" -> 10000;
            default -> 1000;
        };
    }

    private static String hex(SecureRandom rnd, int bytes) {
        StringBuilder sb = new StringBuilder(bytes * 2);
        byte[] b = new byte[bytes];
        rnd.nextBytes(b);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    /** 新建/轮换返回：密钥实体 + 一次性明文。 */
    public record CreatedKey(ApiKey key, String secret) {
    }
}