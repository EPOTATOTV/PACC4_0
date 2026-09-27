package com.potatotv.pacc.ws;

import com.potatotv.pbp.gen.PaccEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PBP 信封（PaccEnvelope）编解码与加验核单测：签名/篡改/重放。 */
class PaccWireCodecTest {

    private static final String SECRET = "test-wss-sign-secret";

    @Test
    void roundTripSignThenVerifyOk() {
        PaccWireCodec c = new PaccWireCodec(SECRET, 60_000, new WssSessionKeys(false));
        PaccEnvelope env = PaccWireCodec.build("inspect_started", "s1", "PT01", "{}", SECRET);
        assertEquals("inspect_started", env.getType());
        assertEquals("s1", env.getSessionId());
        assertEquals(1, env.getSigVersion());
        assertFalse(env.getSignature().isBlank());
        assertTrue(c.verify(env));
    }

    @Test
    void tamperedPayloadFails() {
        PaccWireCodec c = new PaccWireCodec(SECRET, 60_000, new WssSessionKeys(false));
        PaccEnvelope env = PaccWireCodec.build("inspect_forensics", "s2", "PT02", "{\"os\":\"win\"}", SECRET);
        PaccEnvelope tampered = env.toBuilder().setPayloadJson("{\"os\":\"hijacked\"}").build();
        assertFalse(c.verify(tampered));
    }

    @Test
    void wrongSecretFails() {
        PaccWireCodec a = new PaccWireCodec(SECRET, 60_000, new WssSessionKeys(false));
        PaccEnvelope env = PaccWireCodec.build("inspect_offer", "s3", "PT03", "", SECRET);
        PaccWireCodec b = new PaccWireCodec("another-secret", 60_000, new WssSessionKeys(false));
        assertFalse(b.verify(env));
    }

    @Test
    void replayNonceFails() {
        PaccWireCodec c = new PaccWireCodec(SECRET, 60_000, new WssSessionKeys(false));
        PaccEnvelope env = PaccWireCodec.build("inspect_ice", "s4", "PT04", "", SECRET);
        assertTrue(c.verify(env));
        // 同一信封重放 → nonce 已见，拒绝
        assertFalse(c.verify(env));
    }

    @Test
    void differentNoncesDistinctAndVerifiable() {
        PaccWireCodec c = new PaccWireCodec(SECRET, 60_000, new WssSessionKeys(false));
        PaccEnvelope e1 = PaccWireCodec.build("ping", "s5", "", "", SECRET);
        PaccEnvelope e2 = PaccWireCodec.build("ping", "s5", "", "", SECRET);
        assertNotEquals(e1.getNonce(), e2.getNonce());
        assertTrue(c.verify(e1));
        assertTrue(c.verify(e2));
    }
}