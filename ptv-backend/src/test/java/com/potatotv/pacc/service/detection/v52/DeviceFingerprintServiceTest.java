package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Locale;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §7.1/§7.2 设备指纹登记测试：新设备扣分、突变叠加扣分且以指纹为幂等键、
 * 老指纹重复上报不再扣分、摘要格式校验。
 */
class DeviceFingerprintServiceTest {

    private static final String HASH = "a".repeat(64);

    private BehaviorProfileService profiles;
    private ReputationV2Service reputation;
    private DeviceFingerprintService service;

    @BeforeEach
    void setUp() {
        profiles = mock(BehaviorProfileService.class);
        reputation = mock(ReputationV2Service.class);
        when(reputation.score(anyString())).thenReturn(580);
        service = new DeviceFingerprintService(profiles, reputation);
    }

    @Test
    void newDeviceDeductsOnceViaIdempotentEventId() {
        when(profiles.deviceFingerprintSeen("PT1", HASH)).thenReturn(true);
        when(profiles.hasDeviceMutation("PT1")).thenReturn(false);

        DeviceFingerprintService.Registration reg = service.register("PT1", HASH);

        assertTrue(reg.firstSeen());
        assertFalse(reg.mutation());
        assertEquals(580, reg.reputationScore());
        verify(reputation).apply(eq("PT1"), eq(ReputationV2Service.Event.NEW_DEVICE_LOGIN), eq(HASH), anyString());
        verify(reputation, never()).apply(anyString(), eq(ReputationV2Service.Event.FINGERPRINT_MUTATION),
                anyString(), anyString());
    }

    @Test
    void mutatedFingerprintDeductsBothNewDeviceAndMutation() {
        when(profiles.deviceFingerprintSeen("PT1", HASH)).thenReturn(true);
        when(profiles.hasDeviceMutation("PT1")).thenReturn(true);

        DeviceFingerprintService.Registration reg = service.register("PT1", HASH);

        assertTrue(reg.mutation());
        verify(reputation).apply(eq("PT1"), eq(ReputationV2Service.Event.NEW_DEVICE_LOGIN), eq(HASH), anyString());
        verify(reputation).apply(eq("PT1"), eq(ReputationV2Service.Event.FINGERPRINT_MUTATION), eq(HASH), anyString());
    }

    @Test
    void knownFingerprintChangesNothing() {
        when(profiles.deviceFingerprintSeen("PT1", HASH)).thenReturn(false);

        DeviceFingerprintService.Registration reg = service.register("PT1", HASH);

        assertFalse(reg.firstSeen());
        assertFalse(reg.mutation());
        verify(reputation, never()).apply(anyString(), eq(ReputationV2Service.Event.NEW_DEVICE_LOGIN),
                anyString(), anyString());
        verify(reputation, never()).apply(anyString(), eq(ReputationV2Service.Event.FINGERPRINT_MUTATION),
                anyString(), anyString());
    }

    @Test
    void hashIsNormalizedBeforeUse() {
        String upper = HASH.toUpperCase(Locale.ROOT);
        when(profiles.deviceFingerprintSeen("PT1", HASH)).thenReturn(true);

        service.register("PT1", "  " + upper + "  ");

        verify(profiles).deviceFingerprintSeen("PT1", HASH);
    }

    @Test
    void invalidHashIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.register("PT1", "short"));
        assertThrows(IllegalArgumentException.class, () -> service.register("PT1", null));
        assertThrows(IllegalArgumentException.class, () -> service.register("PT1", "z".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> service.register("", HASH));
    }
}