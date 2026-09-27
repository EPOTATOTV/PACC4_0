package com.potatotv.paccclient.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 会话密钥派生与状态机测试。
 * <p>关键用例是 {@link #derivationMatchesServerSideVectors()}：这里钉住的期望值与服务端
 * {@code WssSessionKeysTest} 完全一致。两侧派生一旦分叉，全网验签会静默失败，
 * 且症状只是「消息被丢弃」，极难排查——所以必须用固定向量把两边锁在一起。</p>
 */
class WssSessionKeyTest {

    @Test
    void derivationMatchesServerSideVectors() {
        // 与服务端 WssSessionKeysTest 使用同一组输入与期望值（改动任一侧都会同时挂两边）
        assertEquals("c18046f7307b622a054bbc381cf039036c7f59b1afd6920a20b49ab01a1326e8",
                WssSessionKey.deriveSessionKey("static-secret", "sid-abc", "00ff"));
        assertEquals("b09f21d42222bf97725ca4dde105585a8873e201d826df6856688dc8ff35fa47",
                WssSessionKey.deriveNextKey(
                        "c18046f7307b622a054bbc381cf039036c7f59b1afd6920a20b49ab01a1326e8", "sid-abc", 1));
    }

    @Test
    void keyDependsOnSessionIdAndSalt() {
        String a = WssSessionKey.deriveSessionKey("s", "sid-1", "aa");
        String b = WssSessionKey.deriveSessionKey("s", "sid-2", "aa");
        String c = WssSessionKey.deriveSessionKey("s", "sid-1", "bb");
        String d = WssSessionKey.deriveSessionKey("other", "sid-1", "aa");
        assertNotEquals(a, b);
        assertNotEquals(a, c);
        assertNotEquals(a, d);
    }

    @Test
    void rotationChainsAndIsNotReversible() {
        String k0 = WssSessionKey.deriveSessionKey("s", "sid", "aa");
        String k1 = WssSessionKey.deriveNextKey(k0, "sid", 1);
        String k2 = WssSessionKey.deriveNextKey(k1, "sid", 2);
        assertNotEquals(k0, k1);
        assertNotEquals(k1, k2);
        // 同一输入必须稳定
        assertEquals(k1, WssSessionKey.deriveNextKey(k0, "sid", 1));
    }

    @Test
    void lifecycleStartActivateRotateReset() {
        WssSessionKey sk = new WssSessionKey("static-secret");
        assertFalse(sk.active());
        assertEquals(WssSessionKey.SIG_V1, sk.sigVersion());
        assertEquals("static-secret", sk.signingKey());

        String payload = sk.start();
        assertTrue(payload.contains("salt"));
        assertFalse(sk.active(), "start 后仍未激活，需等服务端 session_ready");

        assertTrue(sk.activate());
        assertTrue(sk.active());
        assertEquals(WssSessionKey.SIG_V2, sk.sigVersion());
        assertEquals(0, sk.epoch());
        // 激活后签名密钥必须已换成会话密钥，而不是静态密钥
        assertNotEquals("static-secret", sk.signingKey());

        // epoch 不连续时拒绝推进（防止乱序 ack 把密钥推到服务端不认的位置）
        assertFalse(sk.commitRotation(5));
        assertEquals(0, sk.epoch());
        assertTrue(sk.commitRotation(1));
        assertEquals(1, sk.epoch());

        sk.reset();
        assertFalse(sk.active());
        assertEquals(WssSessionKey.SIG_V1, sk.sigVersion());
    }

    @Test
    void rotationTriggeredByMessageCount() {
        WssSessionKey sk = new WssSessionKey("s");
        sk.start();
        sk.activate();
        // 未达阈值不触发
        for (int i = 0; i < WssSessionKey.ROTATE_AFTER_MESSAGES - 1; i++) {
            assertEquals(-1, sk.dueForRotation());
        }
        assertEquals(0, sk.dueForRotation(), "达到条数阈值应请求轮换当前 epoch");
    }
}
