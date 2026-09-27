package com.potatotv.paccclient.transport;

import com.potatotv.pbp.gen.PaccEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 客户端 PBP 信封签名器单测（与服务端同构，篡改即验签失败）。 */
class PaccWireSignerTest {

    private final PaccWireSigner signer = new PaccWireSigner("test-secret", "PT01");

    @Test
    void buildSetsFieldsAndSigns() {
        PaccEnvelope env = signer.build("inspect_started", "sess", "{}");
        assertEquals("inspect_started", env.getType());
        assertEquals("sess", env.getSessionId());
        assertEquals("PT01", env.getPteid());
        assertEquals(1, env.getSigVersion());
        assertTrue(signer.signatureMatches(env));
    }

    @Test
    void tamperedPayloadFailsVerification() {
        PaccEnvelope env = signer.build("inspect_forensics", "sess", "{\"os\":\"win\"}")
                .toBuilder().setPayloadJson("{\"os\":\"hijacked\"}").build();
        assertFalse(signer.signatureMatches(env));
    }

    @Test
    void serializableRoundTrip() throws Exception {
        PaccEnvelope env = signer.build("inspect_ice", "s9", "{\"sdp\":\"abc\"}");
        byte[] bytes = env.toByteArray();
        PaccEnvelope restored = PaccEnvelope.parseFrom(bytes);
        assertEquals(env.getSignature(), restored.getSignature());
        assertTrue(signer.signatureMatches(restored));
    }
}