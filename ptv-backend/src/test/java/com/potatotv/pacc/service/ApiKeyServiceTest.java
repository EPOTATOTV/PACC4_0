package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ApiKey;
import com.potatotv.pacc.repository.ApiKeyRepository;
import com.potatotv.pacc.repository.ApiUsageLogRepository;
import com.potatotv.pacc.util.ApiCrypto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private ApiKeyRepository keyRepository;
    private ApiUsageLogRepository usageLogRepository;
    private ApiKeyService service;

    private static final String MASTER = "unit-test-master-secret";

    @BeforeEach
    void setup() throws Exception {
        keyRepository = mock(ApiKeyRepository.class);
        usageLogRepository = mock(ApiUsageLogRepository.class);
        service = new ApiKeyService(keyRepository, usageLogRepository);
        Field f = ApiKeyService.class.getDeclaredField("masterSecret");
        f.setAccessible(true);
        f.set(service, MASTER);
    }

    @Test
    void createEncryptsSecretAtRest() {
        ApiKeyService.CreatedKey c = service.create("测试密钥", "platform", "PRO", "READ",
                "detections,stats", null, null, null, null, "admin");
        assertNotNull(c.secret());
        assertEquals(32, c.secret().length(), "64 位十六进制 secret");
        assertFalse(c.key().getSecretEnc().contains(c.secret()), "落库密文不得包含明文");
        assertEquals("PRO", c.key().getPlan());
        assertEquals(1000, c.key().getRateLimitPerHour());
        assertEquals("detections,stats", c.key().getCategories());
    }

    @Test
    void planSelectsDefaultRateLimit() {
        assertEquals(100, service.create("f", "t", "FREE", "READ", null, null, null, null, null, "a").key().getRateLimitPerHour());
        assertEquals(1000, service.create("p", "t", "PRO", "READ", null, null, null, null, null, "a").key().getRateLimitPerHour());
        assertEquals(10000, service.create("e", "t", "ENTERPRISE", "READ", null, null, null, null, null, "a").key().getRateLimitPerHour());
    }

    @Test
    void secretDecryptsBackWithMaster() {
        ApiKeyService.CreatedKey c = service.create("k", "t", "PRO", "READ", null, null, null, null, null, "a");
        assertEquals(c.secret(), ApiCrypto.decrypt(c.key().getSecretEnc(), MASTER));
    }

    @Test
    void rotateGeneratesNewSecretAndStore() {
        ApiKey original = ApiKey.builder().keyId("kpt_1").secretEnc("old").rateLimitPerHour(1000).build();
        when(keyRepository.findFirstByKeyId("kpt_1")).thenReturn(java.util.Optional.of(original));
        when(keyRepository.save(any(ApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyService.CreatedKey c = service.rotate("kpt_1");
        assertNotEquals("old", original.getSecretEnc());
        assertEquals(c.secret(), ApiCrypto.decrypt(original.getSecretEnc(), MASTER));
    }

    @Test
    void rateExceededUsesWindowCount() {
        ApiKey key = ApiKey.builder().keyId("kpt_2").rateLimitPerHour(1000).build();
        when(usageLogRepository.countByApiKeyIdAndCreatedAtAfter(anyString(), any(Instant.class))).thenReturn(1000L);
        assertTrue(service.rateExceeded(key));

        when(usageLogRepository.countByApiKeyIdAndCreatedAtAfter(anyString(), any(Instant.class))).thenReturn(20L);
        assertFalse(service.rateExceeded(key));
    }
}