package com.potatotv.pacc.protect;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * {@link StringVault} / {@link StringProtectorGenerator} 的往返与生成正确性验证。
 * 覆盖空串、ASCII、多字节 UTF-8（中文）、全 0x00、全 0xFF，并证明密文不含明文字节。
 */
class StringVaultTest {

    private static final int KEY = StringProtectorGenerator.DEFAULT_KEY;

    @Test
    void emptyStringRoundTrips() {
        byte[] enc = StringVault.encrypt("", KEY);
        assertNotNull(enc);
        assertEquals(0, enc.length);
        assertEquals("", StringVault.decrypt(enc, KEY));
    }

    @Test
    void asciiRoundTrips() {
        String plain = "wss://pacc.potatotv.asia/ws/ptv";
        assertEquals(plain, StringVault.decrypt(StringVault.encrypt(plain, KEY), KEY));
    }

    @Test
    void multibyteUtf8RoundTrips() {
        String plain = "中文端点：https://api.potatotv.asia—密钥 pacc.client.signature.secret";
        byte[] enc = StringVault.encrypt(plain, KEY);
        assertEquals(plain, StringVault.decrypt(enc, KEY));
        // 中文是三字节 UTF-8，逐字节 XOR 后仍应无损还原（含代理对之外的常规 BMP 字符）
        assertEquals(plain.getBytes(StandardCharsets.UTF_8).length, enc.length);
    }

    @Test
    void allZeroBytesRoundTrip() {
        byte[] raw = {0x00, 0x00, 0x00, 0x00};
        byte[] enc = StringVault.crypt(raw, KEY);
        assertArrayEquals(raw, StringVault.crypt(enc, KEY));
        // "\u0000\u0000\u0000\u0000" 走字符串路径同样成立
        String plain = "\u0000\u0000\u0000\u0000";
        assertEquals(plain, StringVault.decrypt(StringVault.encrypt(plain, KEY), KEY));
    }

    @Test
    void allOnesBytesRoundTrip() {
        byte[] raw = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        byte[] enc = StringVault.crypt(raw, KEY);
        assertArrayEquals(raw, StringVault.crypt(enc, KEY));
        assertFalse(java.util.Arrays.equals(raw, enc), "全 0xFF 也应被改变");
    }

    @Test
    void ciphertextDoesNotContainPlaintext() {
        String plain = "pacc.client.wss.sign.secret";
        byte[] pt = plain.getBytes(StandardCharsets.UTF_8);
        byte[] enc = StringVault.encrypt(plain, KEY);
        assertFalse(java.util.Arrays.equals(pt, enc), "密文不应等于明文");
        assertFalse(containsSubsequence(enc, pt), "密文不应包含明文字节序列");
    }

    @Test
    void generatedSourceCompilesAndDecrypts() throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        Assumptions.assumeTrue(compiler != null, "需要 JDK（带编译器）而非 JRE");

        List<String> literals = List.of(
                "wss://pacc.potatotv.asia/ws/ptv",
                "https://api.potatotv.asia",
                "pacc.client.wss.sign.secret",
                "中文敏感字面量");

        String pkg = "com.potatotv.paccclient.protect";
        String cls = "PaccSecretStrings";
        String src = StringProtectorGenerator.generate(pkg, cls, KEY, literals);

        // 源码层面也不应残留明文
        for (String literal : literals) {
            assertFalse(src.contains(literal), "生成源码泄漏了明文：" + literal);
        }

        Path tmp = Files.createTempDirectory("pacc-string-protect-test");
        try {
            Path srcFile = tmp.resolve("src/PaccSecretStrings.java");
            Files.createDirectories(srcFile.getParent());
            Files.writeString(srcFile, src, StandardCharsets.UTF_8);
            Path outDir = tmp.resolve("out");
            Files.createDirectories(outDir);

            String cp = System.getProperty("java.class.path");
            int rc = compiler.run(null, null, null, "-encoding", "UTF-8", "-d", outDir.toString(),
                    "-cp", cp, srcFile.toString());
            assertEquals(0, rc, "生成的源码编译失败");

            URL[] urls = {outDir.toUri().toURL()};
            try (URLClassLoader loader = new URLClassLoader(urls, getClass().getClassLoader())) {
                Class<?> generated = Class.forName(pkg + "." + cls, true, loader);
                assertEquals(literals.get(0), generated.getMethod("s0").invoke(null));
                assertEquals(literals.get(1), generated.getMethod("s1").invoke(null));
                assertEquals(literals.get(2), generated.getMethod("s2").invoke(null));
                assertEquals(literals.get(3), generated.getMethod("s3").invoke(null));
                String[] all = (String[]) generated.getMethod("all").invoke(null);
                assertArrayEquals(literals.toArray(new String[0]), all);
            }
        } finally {
            deleteRecursively(tmp);
        }
    }

    @Test
    void generatedSourceForEmptyLiteralListStillCompilesShape() {
        String src = StringProtectorGenerator.generate("com.potatotv.paccclient.protect",
                "PaccSecretStrings", KEY, List.of());
        assertTrue(src.contains("public static String[] all()"));
        assertFalse(src.contains("private static final byte[] S0"));
    }

    /** 判断 {@code haystack} 是否包含连续子序列 {@code needle}。 */
    private static boolean containsSubsequence(byte[] haystack, byte[] needle) {
        if (needle.length == 0 || haystack.length < needle.length) {
            return false;
        }
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // 临时目录清理失败不影响测试结论
                }
            });
        }
    }
}