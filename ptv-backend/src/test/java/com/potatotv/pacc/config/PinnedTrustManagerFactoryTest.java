package com.potatotv.pacc.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** TLS 证书固定工厂单测：未配置时退回默认，配置后启用带 pin 的请求工厂。 */
class PinnedTrustManagerFactoryTest {

    @Test
    void inactiveWhenNoPinConfigured() {
        PinnedTrustManagerFactory f = new PinnedTrustManagerFactory("");
        assertFalse(f.active());
        assertNull(f.requestFactory());
    }

    @Test
    void inactiveWhenOnlyInvalidPins() {
        // 非法的 Base64 顺被忽略 → 视为未启用，保证误配置不误伤出站
        PinnedTrustManagerFactory f = new PinnedTrustManagerFactory("not-a-base64!!,###");
        assertFalse(f.active());
        assertNull(f.requestFactory());
    }

    @Test
    void activeWhenValidPinConfigured() {
        PinnedTrustManagerFactory f = new PinnedTrustManagerFactory("Zm9v"); // base64("foo")
        assertTrue(f.active());
        assertNotNull(f.requestFactory());
    }
}