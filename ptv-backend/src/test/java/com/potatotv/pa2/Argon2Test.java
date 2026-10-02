package com.potatotv.pa2;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    // ---------------- PHC 标准格式（§3.2.1） ----------------

    @Test
    void encodeUsesPhcStandardFormat() {
        String encoded = new Argon2(3, 65536, 1, 32).encode(utf8("Pacc@Test123"), SALT);
        // $argon2id$v=19$m=65536,t=3,p=1$salt$hash（无填充标准 Base64）
        assertTrue(encoded.startsWith("$argon2id$v=19$m=65536,t=3,p=1$"), encoded);
        assertEquals(6, encoded.split("\\$").length);
        Base64.getDecoder().decode(encoded.split("\\$")[4]);
        Base64.getDecoder().decode(encoded.split("\\$")[5]);
    }

    @Test
    void parseRoundTripsEncode() {
        Argon2 argon2 = new Argon2(3, 65536, 1, 32);
        String encoded = argon2.encode(utf8("Pacc@Test123"), SALT);
        Argon2.Argon2Hash parsed = Argon2.parse(encoded);
        assertEquals(0x13, parsed.version());
        assertEquals(65536, parsed.memoryKib());
        assertEquals(3, parsed.iterations());
        assertEquals(1, parsed.parallelism());
        assertArrayEquals(SALT, parsed.salt());
        assertEquals(32, parsed.hash().length);
    }

    @Test
    void verifyAcceptsOwnEncodingAndRejectsWrongPassword() {
        Argon2 argon2 = new Argon2(3, 65536, 1, 32);
        String encoded = argon2.encode(utf8("Pacc@Test123"), SALT);
        assertTrue(argon2.verify(encoded, utf8("Pacc@Test123")));
        assertFalse(argon2.verify(encoded, utf8("Pacc@Test124")));
    }

    @Test
    void verifyAcceptsUrlSafeAlphabet() {
        // 编码固定用标准 Base64，但解析要兼容别处生成的 URL 安全字母表
        Argon2 argon2 = new Argon2(3, 65536, 1, 32);
        String standard = argon2.encode(utf8("Pacc@Test123"), SALT);
        String urlSafe = standard.replace('+', '-').replace('/', '_');
        assertTrue(argon2.verify(urlSafe, utf8("Pacc@Test123")));
    }

    @Test
    void verifyReturnsFalseForMalformedEncoding() {
        Argon2 argon2 = new Argon2(3, 65536, 1, 32);
        assertFalse(argon2.verify(null, utf8("Pacc@Test123")));
        assertFalse(argon2.verify("not-a-phc-string", utf8("Pacc@Test123")));
        assertFalse(argon2.verify("$argon2id$v=16$m=65536,t=3,p=1$c29tZXNhbHQ$AAAAAAAA", utf8("x")));
        assertFalse(argon2.verify("$argon2i$v=19$m=65536,t=3,p=1$c29tZXNhbHQ$AAAA", utf8("x")));
        assertFalse(argon2.verify("$argon2id$v=19$m=65536,t=3$c29tZXNhbHQ$AAAA", utf8("x")));
        assertFalse(argon2.verify("$argon2id$v=19$m=65536,t=3,p=1$!!!$AAAA", utf8("x")));
    }

    @Test
    void parseRejectsMalformedEncoding() {
        assertThrows(IllegalArgumentException.class, () -> Argon2.parse("$argon2id$v=19$m=1,t=1,p=1$abc"));
        assertThrows(IllegalArgumentException.class, () -> Argon2.parse("$argon2id$v=19$m=x,t=1,p=1$abc$def"));
        assertThrows(IllegalArgumentException.class, () -> Argon2.parse("$argon2id$v=16$m=1,t=1,p=1$abc$def"));
    }

    // ---------------- char[] 密码（§3.2.2） ----------------

    @Test
    void charArrayOverloadsMatchByteArray() {
        Argon2 argon2 = new Argon2(3, 65536, 1, 32);
        char[] password = "Pacc@Test123".toCharArray();
        assertArrayEquals(argon2.hash(utf8("Pacc@Test123"), SALT), argon2.hash(password, SALT));

        String encoded = argon2.encode(password, SALT);
        assertEquals(argon2.encode(utf8("Pacc@Test123"), SALT), encoded);
        assertTrue(argon2.verify(encoded, password));
        assertFalse(argon2.verify(encoded, "Pacc@Test124".toCharArray()));
    }

    @Test
    void charArrayEncodesUtf8NotRawChars() {
        Argon2 argon2 = new Argon2(2, 256, 1, 32);
        char[] password = "密码Pacc123!".toCharArray();
        assertArrayEquals(argon2.hash(utf8("密码Pacc123!"), SALT), argon2.hash(password, SALT));
        assertTrue(argon2.verify(argon2.encode(utf8("密码Pacc123!"), SALT), password));
    }

    @Test
    void charArrayRejectsUnpairedSurrogate() {
        Argon2 argon2 = new Argon2(2, 256, 1, 32);
        char[] broken = {'a', '\uD83D', 'b'};
        assertThrows(IllegalArgumentException.class, () -> argon2.hash(broken, SALT));
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(String.format("%02x", x));
        }
        return sb.toString();
    }
}