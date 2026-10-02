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
 * {@code enhance} 标记类上的强化变换。
 *
 * <ul>
 *   <li>{@code bogus_code}：往 return 前塞净栈为 0 的垃圾指令（{@code iconst_0; iconst_1;
 *       iadd; pop}），行为不变；</li>
 *   <li>{@code integrity}：注入 {@code PobGuard} 并在 enhance 类的 {@code <clinit>} 里调
 *       {@code check()}，行为不变。</li>
 * </ul>
 */
class EnhanceTransformsTest {

    private static final String ENTRY = "com.potatotv.paccclient.enh.Enh";
    private static final String ENH_CLASS = "com/potatotv/paccclient/enh/Enh.class";
    private static final String GUARD_CLASS = "com/potatotv/paccclient/PobGuard.class";

    /** {@code iconst_0; iconst_1; iadd; pop} 的字节序列。 */
    private static final byte[] JUNK = {0x03, 0x04, 0x60, 0x57};

    @TempDir
    Path tmp;

    @Test
    void 垃圾代码注入后行为不变() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("bogus"));
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources(), ENTRY);
        assertEquals("10", Fixtures.runMain(original, ENTRY));

        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, """
                keep class com.potatotv.paccclient.enh.Enh
                keep member com.potatotv.paccclient.enh.Enh main
                enhance class com.potatotv.paccclient.enh.Work
                bogus_code = true
                """, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, List.of(), PobRules.parse(rulesFile));

        assertEquals("10", Fixtures.runMain(target, ENTRY), "注入垃圾代码不得改变行为");

        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertTrue(entries.entrySet().stream()
                        .filter(e -> !e.getKey().equals(ENH_CLASS))
                        .anyMatch(e -> contains(e.getValue(), JUNK)),
                "被 enhance 的类里必须出现垃圾指令");
        assertFalse(contains(entries.get(ENH_CLASS), JUNK),
                "未被 enhance 的入口类不应被注入垃圾代码");
    }

    @Test
    void 完整性校验注入后可加载运行() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("integrity"));
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources(), ENTRY);

        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, """
                keep class com.potatotv.paccclient.enh.Enh
                keep member com.potatotv.paccclient.enh.Enh main
                enhance class com.potatotv.paccclient.enh.Work
                integrity = true
                """, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, List.of(), PobRules.parse(rulesFile));

        assertEquals("10", Fixtures.runMain(target, ENTRY), "完整性校验必须通过（哈希一致）");

        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertTrue(entries.containsKey(GUARD_CLASS), "必须注入 PobGuard 类");

        ClassFile workClass = findGuardClient(entries);
        assertTrue(hasClinit(workClass), "原本没有 <clinit> 的 enhance 类应被新建一个");
        assertTrue(referencesClass(workClass, "PobGuard"), "enhance 类必须引用 PobGuard");
    }

    // ------------------------------------------------------------------

    /** 在产物里找出被注入校验、引用 PobGuard 的那个（非 Guard、非保留入口）类。 */
    private static ClassFile findGuardClient(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!e.getKey().endsWith(".class") || e.getKey().equals(GUARD_CLASS)
                    || e.getKey().equals(ENH_CLASS)) {
                continue;
            }
            ClassFile cf = ClassFile.read(e.getValue());
            if (referencesClass(cf, "PobGuard")) {
                return cf;
            }
        }
        throw new AssertionError("找不到引用 PobGuard 的被校验类");
    }

    private static boolean hasClinit(ClassFile cf) {
        for (ClassFile.Member m : cf.methods()) {
            if ("<clinit>".equals(cf.utf8(m.nameIndex))) {
                return true;
            }
        }
        return false;
    }

    private static boolean referencesClass(ClassFile cf, String simpleName) {
        for (ClassFile.Cp c : cf.constantPool()) {
            if (c != null && c.tag == ClassFile.C_CLASS) {
                String name = cf.utf8(c.a);
                if (name != null && name.endsWith(simpleName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Enh", """
                package com.potatotv.paccclient.enh;

                public final class Enh {
                    public static void main(String[] args) {
                        System.out.println(Work.total());
                    }
                }
                """);
        sources.put("Work", """
                package com.potatotv.paccclient.enh;

                public final class Work {
                    public static int total() {
                        int sum = 0;
                        for (int i = 1; i <= 4; i++) {
                            sum += i;
                        }
                        return sum;
                    }
                }
                """);
        return sources;
    }
}