package com.potatotv.paccclient.transport;

import com.potatotv.pacc.proto.PaccWire;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 客户端 protobuf 信封签名器单测（与服务端同构规范字段，篡改即验签失败）。 */
class PaccWireSignerTest {

    private final PaccWireSigner signer = new PaccWireSigner("test-secret", "PT01");

    @Test
    void buildSetsFieldsAndSigns() {
        PaccWire.WsEnvelope env = signer.build("inspect_started", "sess", "{}");
        assertEquals("inspect_started", env.getType());
        assertEquals("sess", env.getSessionId());
        assertEquals("PT01", env.getPteid());
        assertEquals(1, env.getSigVersion());
        assertTrue(signer.signatureMatches(env));
    }

    @Test
    void tamperedPayloadFailsVerification() {
        PaccWire.WsEnvelope env = signer.build("inspect_forensics", "sess", "{\"os\":\"win\"}")
                .toBuilder().setPayloadJson("{\"os\":\"hijacked\"}").build();
        assertFalse(signer.signatureMatches(env));
    }

    @Test
    void serializableRoundTrip() throws Exception {
        PaccWire.WsEnvelope env = signer.build("inspect_ice", "s9", "{\"sdp\":\"abc\"}");
        byte[] bytes = env.toByteArray();
        PaccWire.WsEnvelope restored = PaccWire.WsEnvelope.parseFrom(bytes);
        assertEquals(env.getSignature(), restored.getSignature());
        assertTrue(signer.signatureMatches(restored));
    }
}