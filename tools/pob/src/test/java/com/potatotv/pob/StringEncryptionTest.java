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

    @Test
    void URL与API路径加密而内部类名样串保持明文() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work3"));
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Paths", """
                package com.potatotv.paccclient.secret;

                public final class Paths {
                    public static void main(String[] args) {
                        System.out.println("https://api.potatotv.asia/v1/report");
                        System.out.println("/api/player/ops/report/telemetry");
                        System.out.println("com/example/NotAClass");
                    }
                }
                """);
        String entry = "com.potatotv.paccclient.secret.Paths";
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources, entry);
        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, """
                keep class com.potatotv.paccclient.secret.Paths
                keep member com.potatotv.paccclient.secret.Paths main
                encrypt_strings = true
                """, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, java.util.List.of(), PobRules.parse(rulesFile));

        String out = Fixtures.runMain(target, entry);
        assertTrue(out.contains("https://api.potatotv.asia/v1/report"), "URL 运行时必须还原");
        assertTrue(out.contains("/api/player/ops/report/telemetry"), "API 路径运行时必须还原");
        assertTrue(out.contains("com/example/NotAClass"), "类名样串运行时必须还原");

        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertFalse(Fixtures.containsPlaintext(entries, "https://api.potatotv.asia"),
                "含 :// 的 URL 必须加密（验收 V01：反编译看不到 API 地址）");
        assertFalse(Fixtures.containsPlaintext(entries, "/api/player/ops/report/telemetry"),
                "以 / 开头的 API 路径必须加密");
        assertTrue(Fixtures.containsPlaintext(entries, "com/example/NotAClass"),
                "形如内部类名的串保持明文，避免误伤 Class.forName 实参");
    }

    @Test
    void 字段常量与invokedynamic拼接配方都要加密且运行时还原() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work4"));
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Endpoints", """
                package com.potatotv.paccclient.secret;

                public final class Endpoints {
                    private static final String HOST = "pacc.potatotv.asia";
                    private static final String DEFAULT_BASE = "https://api.potatotv.asia/v1/report";

                    public static void main(String[] args) throws Exception {
                        System.out.println(route("telemetry"));
                        java.lang.reflect.Field host = Endpoints.class.getDeclaredField("HOST");
                        host.setAccessible(true);
                        System.out.println(host.get(null));
                        java.lang.reflect.Field base = Endpoints.class.getDeclaredField("DEFAULT_BASE");
                        base.setAccessible(true);
                        System.out.println(base.get(null));
                    }

                    private static String route(String kind) {
                        return "https://api.potatotv.asia/v1" + "/report/" + kind;
                    }
                }
                """);
        String entry = "com.potatotv.paccclient.secret.Endpoints";
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources, entry);
        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, """
                keep class com.potatotv.paccclient.secret.Endpoints all
                encrypt_strings = true
                """, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, java.util.List.of(), PobRules.parse(rulesFile));

        // println 用平台行分隔符（Windows 是 \r\n），比较前统一成 \n
        String out = Fixtures.runMain(target, entry).replace("\r\n", "\n");
        assertEquals("https://api.potatotv.asia/v1/report/telemetry\npacc.potatotv.asia\n"
                + "https://api.potatotv.asia/v1/report", out,
                "字段常量与拼接配方在运行时都必须还原（<clinit> 赋值 / 引导方法解密）");

        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertTrue(entries.containsKey("com/potatotv/paccclient/PobVault.class"), "必须注入 PobVault");
        assertTrue(entries.containsKey("com/potatotv/paccclient/PobConcat.class"), "必须注入 PobConcat");
        assertFalse(Fixtures.containsPlaintext(entries, "https://api.potatotv.asia"),
                "字段常量与拼接配方里的 URL 都不得以明文残留");
        assertFalse(Fixtures.containsPlaintext(entries, "pacc.potatotv.asia"),
                "static final 字段常量必须改到 <clinit> 解密赋值");
        assertFalse(Fixtures.containsPlaintext(entries, "/report/"),
                "invokedynamic 拼接配方里的字面量必须加密");
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