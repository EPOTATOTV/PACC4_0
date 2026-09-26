package com.potatotv.pacc.ws;

import com.potatotv.pbp.gen.PaccEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WSS 会话密钥测试。
 * <p>{@link #derivationMatchesClientSideVectors()} 与客户端 {@code WssSessionKeyTest}
 * 共用同一组输入与期望值：两侧派生一旦分叉，全网信封会静默验签失败，必须靠固定向量锁住。</p>
 */
class WssSessionKeysTest {

    private static final String STATIC_SECRET = "static-secret";

    // -------------------------------- 派生向量（与客户端一致） --------------------------------

    @Test
    void derivationMatchesClientSideVectors() {
        assertEquals("c18046f7307b622a054bbc381cf039036c7f59b1afd6920a20b49ab01a1326e8",
                WssSessionKeys.deriveSessionKey(STATIC_SECRET, "sid-abc", "00ff"));
        assertEquals("b09f21d42222bf97725ca4dde105585a8873e201d826df6856688dc8ff35fa47",
                WssSessionKeys.deriveNextKey(
                        "c18046f7307b622a054bbc381cf039036c7f59b1afd6920a20b49ab01a1326e8", "sid-abc", 1));
    }

    @Test
    void openRejectsMalformedSalt() {
        WssSessionKeys keys = new WssSessionKeys(false);
        // 盐必须是十六进制串：挡住把任意内容（乃至密钥本身）塞进盐里
        assertNull(keys.open("sid", "not-hex!!", "PT1", STATIC_SECRET));
        assertNull(keys.open("sid", "abc", "PT1", STATIC_SECRET), "过短的盐也应拒绝");
        assertNull(keys.open("", "0011223344556677", "PT1", STATIC_SECRET));
        assertNotNull(keys.open("sid", "0011223344556677", "PT1", STATIC_SECRET));
    }

    @Test
    void rotateRequiresMatchingEpoch() {
        WssSessionKeys keys = new WssSessionKeys(false);
        keys.open("sid", "0011223344556677", "PT1", STATIC_SECRET);
        // epoch 不符 → 拒绝，防止重放旧的 rekey 把密钥回退
        assertNull(keys.rotate("sid", 3));
        assertEquals(0, keys.get("sid").epoch());

        WssSessionKeys.Session rotated = keys.rotate("sid", 0);
        assertNotNull(rotated);
        assertEquals(1, rotated.epoch());
        // 链式轮换：新密钥不等于 epoch0 的密钥
        assertNotEquals(keys.get("sid").keyHex(), WssSessionKeys.deriveSessionKey(
                STATIC_SECRET, "sid", "0011223344556677"));
    }

    @Test
    void closeDropsKey() {
        WssSessionKeys keys = new WssSessionKeys(false);
        keys.open("sid", "0011223344556677", "PT1", STATIC_SECRET);
        assertNotNull(keys.get("sid"));
        keys.close("sid");
        assertNull(keys.get("sid"), "断开后密钥必须立即消失，不留在内存等 TTL");
    }

    // -------------------------------- 与 PaccWireCodec 的联动 --------------------------------

    @Test
    void v2EnvelopeVerifiedWithSessionKey() {
        WssSessionKeys keys = new WssSessionKeys(false);
        PaccWireCodec codec = new PaccWireCodec(STATIC_SECRET, 60_000L, keys);
        WssSessionKeys.Session s = keys.open("sid-1", "0011223344556677", "PT1", STATIC_SECRET);

        PaccEnvelope env = codec.buildWithSessionKey(
                "inspect_offer", "sid-1", "PT1", "{\"sdp\":\"x\"}", s.keyHex());
        assertEquals(WssSessionKeys.SIG_V2, env.getSigVersion());
        assertTrue(codec.verify(env));
    }

    @Test
    void v2EnvelopeWithUnknownSessionRejected() {
        WssSessionKeys keys = new WssSessionKeys(false);
        PaccWireCodec codec = new PaccWireCodec(STATIC_SECRET, 60_000L, keys);
        // 未握手就发 v2：会话不存在，无法验签
        PaccEnvelope env = codec.buildWithSessionKey(
                "inspect_offer", "sid-unknown", "PT1", "{}", "deadbeef");
        assertFalse(codec.verify(env));
    }

    @Test
    void v2EnvelopeFromOtherPlayerRejected() {
        WssSessionKeys keys = new WssSessionKeys(false);
        PaccWireCodec codec = new PaccWireCodec(STATIC_SECRET, 60_000L, keys);
        WssSessionKeys.Session s = keys.open("sid-1", "0011223344556677", "PT1", STATIC_SECRET);
        // 拿 PT1 的会话密钥签 PT2 的身份 → 会话与玩家绑定，拒绝
        PaccEnvelope env = codec.buildWithSessionKey(
                "inspect_offer", "sid-1", "PT2", "{}", s.keyHex());
        assertFalse(codec.verify(env));
    }

    @Test
    void requiredModeRejectsV1ButAcceptsV2() {
        WssSessionKeys keys = new WssSessionKeys(true);
        PaccWireCodec codec = new PaccWireCodec(STATIC_SECRET, 60_000L, keys);

        // 强制模式下静态密钥信封一律拒绝：否则持有静态密钥者可直接绕过会话密钥
        PaccEnvelope v1 = PaccWireCodec.build("inspect_offer", "sid-1", "PT1", "{}", STATIC_SECRET);
        assertFalse(codec.verify(v1));

        WssSessionKeys.Session s = keys.open("sid-1", "0011223344556677", "PT1", STATIC_SECRET);
        assertTrue(codec.verify(codec.buildWithSessionKey("inspect_offer", "sid-1", "PT1", "{}", s.keyHex())));
    }

    @Test
    void compatibilityModeStillAcceptsV1() {
        WssSessionKeys keys = new WssSessionKeys(false);
        PaccWireCodec codec = new PaccWireCodec(STATIC_SECRET, 60_000L, keys);
        // 默认（非强制）下旧客户端不受影响
        assertTrue(codec.verify(PaccWireCodec.build("inspect_offer", "sid-1", "PT1", "{}", STATIC_SECRET)));
    }

    @Test
    void sessionKeyRotatedServerSideInvalidatesOldKey() {
        WssSessionKeys keys = new WssSessionKeys(false);
        PaccWireCodec codec = new PaccWireCodec(STATIC_SECRET, 60_000L, keys);
        WssSessionKeys.Session s0 = keys.open("sid-1", "0011223344556677", "PT1", STATIC_SECRET);
        keys.rotate("sid-1", 0);

        // 轮换后用旧密钥签的信封必须失败，否则轮换等于没做
        PaccEnvelope stale = codec.buildWithSessionKey("inspect_offer", "sid-1", "PT1", "{}", s0.keyHex());
        assertFalse(codec.verify(stale));

        WssSessionKeys.Session s1 = keys.get("sid-1");
        assertTrue(codec.verify(codec.buildWithSessionKey("inspect_offer", "sid-1", "PT1", "{}", s1.keyHex())));
    }

    @Test
    void newSaltOnReconnectYieldsDifferentKey() {
        WssSessionKeys keys = new WssSessionKeys(false);
        String salt1 = keys.newSalt();
        String salt2 = keys.newSalt();
        assertNotEquals(salt1, salt2, "每次协商都应生成新盐");
        assertNotEquals(
                WssSessionKeys.deriveSessionKey(STATIC_SECRET, "sid", salt1),
                WssSessionKeys.deriveSessionKey(STATIC_SECRET, "sid", salt2));
    }
}
