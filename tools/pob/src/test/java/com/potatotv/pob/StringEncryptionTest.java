package com.potatotv.pob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 字符串加密（{@code encrypt_strings = true}）：把 {@code ldc String} 换成
 * {@code ldc_w int + invokestatic PobVault.get(I)}，并要求
 * <ol>
 *   <li>混淆前后程序输出逐字节一致（含 tableswitch / 循环分支，验证偏移重算正确）；</li>
 *   <li>产物里不再出现被加密串的明文；</li>
 *   <li>注入的解密库类存在，且程序能真正加载运行（无 VerifyError）。</li>
 * </ol>
 */
class StringEncryptionTest {

    private static final String PKG = "com/potatotv/paccclient/secret";
    private static final String ENTRY = "com.potatotv.paccclient.secret.Sec";
    private static final String EXPECTED =
            "ALPHA_SECRET0ALPHA_SECRET1ALPHA_SECRET2ALPHA_SECRET3BANNER_END";

    private static final String RULES = """
            keep class com.potatotv.paccclient.secret.Sec
            keep member com.potatotv.paccclient.secret.Sec main
            encrypt_strings = true
            """;

    @TempDir
    Path tmp;

    @Test
    void 加密后行为一致且产物中无明文() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources(), ENTRY);
        assertEquals(EXPECTED, Fixtures.runMain(original, ENTRY), "样例本身的行为");

        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, RULES, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, java.util.List.of(), PobRules.parse(rulesFile));

        assertEquals(EXPECTED, Fixtures.runMain(target, ENTRY),
                "加密后行为必须一致（偏移重算 / tableswitch 对齐都必须正确）");

        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertTrue(entries.containsKey("com/potatotv/paccclient/PobVault.class"),
                "必须注入解密库类 PobVault");
        assertFalse(Fixtures.containsPlaintext(entries, "ALPHA_SECRET"),
                "被加密的字符串不得以明文残留在产物里");
        assertFalse(Fixtures.containsPlaintext(entries, "BANNER_END"),
                "main 里的字符串同样要被加密");
        assertTrue(entries.keySet().stream().anyMatch(n -> n.matches(PKG + "/Sec\\.class")),
                "被 keep 的入口类原名保留");
    }

    @Test
    void 白名单与最小长度可排除指定字符串() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work2"));
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources(), ENTRY);
        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, """
                keep class com.potatotv.paccclient.secret.Sec
                keep member com.potatotv.paccclient.secret.Sec main
                encrypt_strings = true
                encrypt_strings_keep = "BANNER_END"
                """, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, java.util.List.of(), PobRules.parse(rulesFile));

        assertEquals(EXPECTED, Fixtures.runMain(target, ENTRY));
        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertTrue(Fixtures.containsPlaintext(entries, "BANNER_END"),
                "白名单串必须原样保留明文");
        assertFalse(Fixtures.containsPlaintext(entries, "ALPHA_SECRET"),
                "未被白名单的串仍要加密");
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Sec", """
                package com.potatotv.paccclient.secret;

                public final class Sec {
                    public static void main(String[] args) {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < 4; i++) {
                            sb.append(pick(i));
                        }
                        sb.append("BANNER_END");
                        System.out.println(sb);
                    }

                    private static String pick(int i) {
                        switch (i) {
                            case 0:
                                return "ALPHA_SECRET0";
                            case 1:
                                return "ALPHA_SECRET1";
                            case 2:
                                return "ALPHA_SECRET2";
                            case 3:
                                return "ALPHA_SECRET3";
                            default:
                                return "OMEGA_DEFAULT";
                        }
                    }
                }
                """);
        return sources;
    }
}