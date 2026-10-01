package com.potatotv.pa2;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * PA2（自研 Argon2id + Blake2b）单元测试。
 *
 * <p>基准向量由 BouncyCastle 1.86 的 {@code Argon2BytesGenerator} 与
 * {@code Blake2bDigest} 生成，用于锁定替换后在相同参数下逐字节一致，
 * 保证存量密码哈希不受影响。</p>
 */
class Argon2Test {

    /** 固定盐：0x10..0x1f，与生成基准向量时一致。 */
    private static final byte[] SALT = {
            0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17,
            0x18, 0x19, 0x1a, 0x1b, 0x1c, 0x1d, 0x1e, 0x1f
    };

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String b64(byte[] b) {
        return Base64.getEncoder().encodeToString(b);
    }

    /** 递增字节序列 0x00, 0x01, ...，用于跨块路径的回归向量。 */
    private static byte[] seq(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) {
            b[i] = (byte) i;
        }
        return b;
    }

    @Test
    void blake2bMatchesRfc7693Vector() {
        // BLAKE2b-512("abc")
        assertEquals("ba80a53f981c4d0d6a2797b69f12f6e94c212f14685ac4b74b12bb6fdbffa2d1"
                        + "7d87c5392aab792dc252d5de4533cc9518d38aa8dbf1925ab92386edd4009923",
                toHex(Blake2b.digest(64, utf8("abc"))));
    }

    @Test
    void blake2bEmptyAndBlockBoundary() {
        // 空输入与正好 128 字节属于 BLAKE2b 的边界，摘要长度也要可变（32 字节）
        assertEquals(32, Blake2b.digest(32, new byte[0]).length);
        assertEquals(64, Blake2b.digest(64, new byte[128]).length);
        assertEquals(1, Blake2b.digest(1, utf8("pacc")).length);
    }

    @Test
    void blake2bMatchesBouncyCastleAcrossBlocks() {
        // 多块路径：非末尾块的 t 计数器必须计入该块字节数，否则结果偏移
        assertEquals("f59711d44a031d5f97a9413c065d1e614c417ede998590325f49bad2fd444d3e"
                        + "4418be19aec4e11449ac1a57207898bc57d76a1bcf3566292c20c683a5c4648f",
                toHex(Blake2b.digest(64, seq(129))));
        // 多块 + 非标准摘要长度（256 位）组合
        assertEquals("64a7891133a87d13886a0ca2cf351cca4933a34c189d9c9a491fb35e5dccdd51",
                toHex(Blake2b.digest(32, seq(1028))));
    }

    @Test
    void matchesBouncyCastleProductionParams() {
        // 生产参数：t=3, m=65536KB, p=1, T=32（与 AccountService 一致）
        Argon2 argon2 = new Argon2(3, 65536, 1, 32);
        assertEquals("Omy5aDh4OznsXA+CFgkxV270oGKwrvekcZmAkBwUyss=",
                b64(argon2.hash(utf8("Pacc@Test123"), SALT)));
        assertEquals("UcNncEYj7IfyUeP+JI/C62uW/WkW0/TIM9SABfbNo+A=",
                b64(argon2.hash(utf8("aA1!bB2@cC3#"), SALT)));
    }

    @Test
    void matchesBouncyCastleMultiLaneAndLongOutput() {
        assertEquals("bB9H/28oGNZ0GnbucE7HrZUtVMHl2j9Zpm4UkZANxvk=",
                b64(new Argon2(2, 256, 1, 32).hash(utf8("Pacc@Test123"), SALT)));
        // 多 lane 走跨 lane 引用路径
        assertEquals("Zfe9LS1nR9lHfNtULwTFQQEERXdTfLo9qJnTMy72vn4=",
                b64(new Argon2(2, 256, 2, 32).hash(utf8("Pacc@Test123"), SALT)));
        // 输出 > 64 字节走 H' 的链式派生路径
        assertEquals("Uj0c/99kUd6ND9gLxspftbwfim45bLz0hyml3An9/z37iihxSMBMEtVufgFGAOG/IPyLsN6lZ9d4i3SmUJ0WLg==",
                b64(new Argon2(2, 256, 1, 64).hash(utf8("Pacc@Test123"), SALT)));
    }

    @Test
    void saltAndPasswordChangeOutput() {
        Argon2 argon2 = new Argon2(2, 256, 1, 32);
        byte[] base = argon2.hash(utf8("Pacc@Test123"), SALT);
        byte[] otherPassword = argon2.hash(utf8("Pacc@Test124"), SALT);
        byte[] otherSalt = argon2.hash(utf8("Pacc@Test123"), utf8("0123456789abcdef"));
        assertNotEquals(toHex(base), toHex(otherPassword));
        assertNotEquals(toHex(base), toHex(otherSalt));
        // 同输入必须可复现
        assertArrayEquals(base, argon2.hash(utf8("Pacc@Test123"), SALT));
    }

    @Test
    void rejectsIllegalParameters() {
        assertThrows(IllegalArgumentException.class, () -> new Argon2(0, 1024, 1, 32));
        assertThrows(IllegalArgumentException.class, () -> new Argon2(1, 1024, 0, 32));
        assertThrows(IllegalArgumentException.class, () -> new Argon2(1, 1024, 1, 3));
        assertThrows(IllegalArgumentException.class, () -> new Argon2(1, 4, 1, 32));
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}