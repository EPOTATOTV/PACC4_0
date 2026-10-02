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
 * 全流水线：字符串加密 / 控制流平坦化 / 垃圾代码 / 完整性校验 / Unicode 命名同时开启，
 * 产物必须仍能加载并跑出与原始程序一致的结果。
 *
 * <p>单看每个变换都有自己的用例，但它们的<b>顺序</b>才是最容易出问题的地方——字符串加密要
 * 重算偏移、平坦化要重建 StackMapTable、垃圾代码插在 return 前、完整性校验的哈希必须覆盖
 * 前面所有改动。这里用一条端到端把顺序钉死。</p>
 */
class FullPipelineTest {

    private static final String ENTRY = "com.potatotv.paccclient.full.Full";
    private static final String VAULT_CLASS = "com/potatotv/paccclient/PobVault.class";
    private static final String GUARD_CLASS = "com/potatotv/paccclient/PobGuard.class";

    /** {@code iconst_0; iconst_1; iadd; pop} 的字节序列（垃圾代码指纹）。 */
    private static final byte[] JUNK = {0x03, 0x04, 0x60, 0x57};

    @TempDir
    Path tmp;

    @Test
    void 全部变换同时开启后行为不变() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("full"));
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources(), ENTRY);
        String expected = Fixtures.runMain(original, ENTRY);
        assertEquals("picked-4:4", expected);

        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, """
                keep class com.potatotv.paccclient.full.Full
                keep member com.potatotv.paccclient.full.Full main
                enhance class com.potatotv.paccclient.full.Picked
                encrypt_strings = true
                unicode_names = true
                bogus_code = true
                integrity = true
                flatten = true
                """, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, List.of(), PobRules.parse(rulesFile));

        assertEquals(expected, Fixtures.runMain(target, ENTRY), "全流水线不得改变行为");

        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertTrue(entries.containsKey(VAULT_CLASS), "开启字符串加密必须注入 PobVault");
        assertTrue(entries.containsKey(GUARD_CLASS), "开启完整性校验必须注入 PobGuard");
        assertTrue(containsSwitch(entries), "开启平坦化后产物里应出现 tableswitch 状态机");
        assertTrue(anyEntryContains(entries, JUNK), "开启垃圾代码后 enhance 类里应出现垃圾指令");
    }

    // ------------------------------------------------------------------

    private static boolean containsSwitch(Map<String, byte[]> entries) {
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (!e.getKey().endsWith(".class")) {
                continue;
            }
            ClassFile cf = ClassFile.read(e.getValue());
            for (ClassFile.Member m : cf.methods()) {
                for (ClassFile.Attr attr : m.attributes) {
                    if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                        continue;
                    }
                    for (Bytecode.Insn insn : Bytecode.scan(Bytecode.readCode(attr.info))) {
                        if (insn.opcode == Bytecode.TABLESWITCH) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean anyEntryContains(Map<String, byte[]> entries, byte[] needle) {
        for (byte[] bytes : entries.values()) {
            if (contains(bytes, needle)) {
                return true;
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
        sources.put("Full", """
                package com.potatotv.paccclient.full;

                public final class Full {
                    public static void main(String[] args) {
                        System.out.println(Picked.label() + ":" + Picked.choose(3, 1, 2));
                    }
                }
                """);
        sources.put("Picked", """
                package com.potatotv.paccclient.full;

                public final class Picked {
                    public static int choose(int a, int b, int c) {
                        if (a > b) {
                            if (b > c) { return a + c; }
                            return a + b;
                        }
                        if (a > c) { return b + c; }
                        return a * b;
                    }

                    public static String label() {
                        String prefix = "picked-";
                        return prefix.concat(Integer.toString(choose(2, 3, 1)));
                    }
                }
                """);
        return sources;
    }
}