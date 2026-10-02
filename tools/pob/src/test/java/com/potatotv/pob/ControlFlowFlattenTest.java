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
 * 控制流平坦化端到端。
 *
 * <p>样例里 {@code choose}/{@code sign} 是「有分支、但从不给局部变量赋值」的方法，落在
 * {@link ControlFlowFlattener} 的保守子集内，应当被改造成 {@code switch(state)} 状态机；
 * {@code sumTo} 用了循环和局部变量赋值，不满足条件，必须原样放行。两条都要钉住：前者证明
 * 平坦化确实发生且行为不变，后者证明「拿不准就跳过」没有退化成「拿不准也乱改」。</p>
 */
class ControlFlowFlattenTest {

    private static final String ENTRY = "com.potatotv.paccclient.flt.Fl";
    private static final String ENTRY_CLASS = "com/potatotv/paccclient/flt/Fl.class";

    @TempDir
    Path tmp;

    @Test
    void 控制流平坦化后行为不变且保守跳过不适用方法() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("flatten"));
        Path original = Fixtures.jar(work, work.resolve("original.jar"), sources(), ENTRY);
        String expected = Fixtures.runMain(original, ENTRY);
        assertEquals("4,-1,10", expected);

        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, """
                keep class com.potatotv.paccclient.flt.Fl all
                flatten = true
                """, StandardCharsets.UTF_8);

        new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient")
                .run(target, List.of(), PobRules.parse(rulesFile));

        assertEquals(expected, Fixtures.runMain(target, ENTRY), "平坦化不得改变行为");

        Map<String, byte[]> entries = Fixtures.readJar(target);
        assertFalse(containsSwitch(Fixtures.readJar(original)), "原始产物不应有 tableswitch");

        ClassFile fl = ClassFile.read(entries.get(ENTRY_CLASS));
        assertTrue(methodHasSwitch(fl, "choose"), "choose 应被改造成 switch 状态机");
        assertTrue(methodHasSwitch(fl, "sign"), "sign 应被改造成 switch 状态机");
        assertFalse(methodHasSwitch(fl, "sumTo"), "带局部变量赋值的循环方法必须原样放行");
    }

    // ------------------------------------------------------------------

    private static boolean methodHasSwitch(ClassFile cf, String methodName) {
        for (ClassFile.Member m : cf.methods()) {
            if (!methodName.equals(cf.utf8(m.nameIndex))) {
                continue;
            }
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
        return false;
    }

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

    private static Map<String, String> sources() {
        Map<String, String> sources = new LinkedHashMap<>();
        sources.put("Fl", """
                package com.potatotv.paccclient.flt;

                public final class Fl {
                    public static int choose(int a, int b, int c) {
                        if (a > b) {
                            if (b > c) { return a + c; }
                            return a + b;
                        }
                        if (a > c) { return b + c; }
                        return a * b;
                    }

                    public static int sign(int a) {
                        if (a > 0) { return 1; }
                        if (a < 0) { return -1; }
                        return 0;
                    }

                    /** 循环 + 局部变量赋值：不在保守子集内，必须被跳过。 */
                    public static int sumTo(int n) {
                        int sum = 0;
                        for (int i = 1; i <= n; i++) {
                            sum += i;
                        }
                        return sum;
                    }

                    public static void main(String[] args) {
                        System.out.println(choose(3, 1, 2) + "," + sign(-5) + "," + sumTo(4));
                    }
                }
                """);
        return sources;
    }
}