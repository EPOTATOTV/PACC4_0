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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unicode 私有区命名（{@code unicode_names = true}）：短名池改用 U+E000..U+F8FF，
 * 产物类名应落在 {@code com/potatotv/paccclient/[\uE000-\uF8FF]{1,2}.class} 形态，
 * 且程序仍能加载运行。默认关闭时走 ASCII 短名（见 {@link ObfuscationTest}）。
 */
class UnicodeNamingTest {

    private static final String ENTRY = "com.potatotv.paccclient.uni.Uni";

    private static final String RULES = """
            keep class com.potatotv.paccclient.uni.Uni
            keep member com.potatotv.paccclient.uni.Uni main
            unicode_names = true
            """;

    @TempDir
    Path tmp;

    @Test
    void 私有区短名且行为不变() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources(), ENTRY);
        assertEquals("aux-10", Fixtures.runMain(original, ENTRY));

        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, RULES, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, List.of(), PobRules.parse(rulesFile));

        assertEquals("aux-10", Fixtures.runMain(target, ENTRY),
                "Unicode 类名同样要能正确加载与调用");

        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertTrue(entries.containsKey("com/potatotv/paccclient/uni/Uni.class"),
                "被 keep 的入口类原名保留");
        assertTrue(entries.keySet().stream()
                        .anyMatch(n -> n.matches("com/potatotv/paccclient/[\uE000-\uF8FF]{1,2}\\.class")),
                "被搬走的类必须用 Unicode 私有区字符做短名");
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Uni", """
                package com.potatotv.paccclient.uni;

                public final class Uni {
                    public static void main(String[] args) {
                        System.out.println(Aux.tag() + "-" + Aux.rolls());
                    }
                }
                """);
        sources.put("Aux", """
                package com.potatotv.paccclient.uni;

                public final class Aux {
                    public static String tag() {
                        return "aux";
                    }

                    public static int rolls() {
                        int sum = 0;
                        for (int i = 0; i < 5; i++) {
                            sum += i;
                        }
                        return sum;
                    }
                }
                """);
        return sources;
    }
}