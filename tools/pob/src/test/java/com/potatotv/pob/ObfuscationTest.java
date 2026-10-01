package com.potatotv.pob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端：把样例程序编译打包、整体混淆，然后要求
 * <ol>
 *   <li>混淆前后程序输出逐字节一致（语义没被改变）；</li>
 *   <li>产物满足 CI 对发行件的几条硬约束（入口类在、原始业务包没了、短名类在、无目录条目）；</li>
 *   <li>类名字符串（{@code Class.forName} 的实参）被同步改写；</li>
 *   <li>源码调试属性被剥离；</li>
 *   <li>与自研成员同名同描述符的 JDK 成员引用不被误改（样例里的 Loader.load / Properties.load）。</li>
 * </ol>
 */
class ObfuscationTest {

    private static final String RULES = """
            keep class com.potatotv.paccclient.fx.Fx
            keep member com.potatotv.paccclient.fx.Fx main
            """;

    private static final String EXPECTED_OUTPUT =
            "hi-a,4,5,6,28,RED,2,3,true,SECRET,task,true,1,used,1,ours,v";

    /** 样例里除入口 Fx 之外的原始类名，混淆后一个都不该留下。 */
    private static final List<String> MOVED_CLASSES = List.of(
            "Greeter", "GreeterImpl", "Sink", "Base", "Derived", "Chain",
            "Color", "Point", "Secret", "Task", "Resource", "FxHelper", "Loader");

    @TempDir
    Path tmp;

    @Test
    void 混淆前后行为一致且产物符合加固要求() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work"));
        Path original = Fixtures.compileAndJar(work, work.resolve("original.jar"));
        Path target = work.resolve("obfuscated.jar");
        Files.copy(original, target);
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, RULES, StandardCharsets.UTF_8);
        Path mapping = work.resolve("pob-mapping.txt");

        assertEquals(EXPECTED_OUTPUT, runMain(original), "样例程序本身的行为");

        new JarObfuscator(mapping, "com/potatotv/paccclient")
                .run(target, List.of(), PobRules.parse(rulesFile));

        assertEquals(EXPECTED_OUTPUT, runMain(target), "混淆后行为必须完全一致（含 Class.forName 路径）");

        Map<String, byte[]> entries = read(target);
        Set<String> names = entries.keySet();
        assertTrue(names.contains("com/potatotv/paccclient/fx/Fx.class"), "入口类必须保留原名");
        for (String moved : MOVED_CLASSES) {
            assertFalse(names.contains(Fixtures.PKG + "/" + moved + ".class"),
                    moved + " 必须已被搬走（对应 CI 的「原始业务包路径必须消失」断言）");
        }
        assertTrue(names.stream().anyMatch(n -> n.matches("com/potatotv/paccclient/[a-z]{1,3}\\.class")),
                "必须存在短名混淆类");
        // 保留类的嵌套类必须落在外层同一个包里：JVMS 要求 nest host 与成员同包，
        // 拆开的话匿名类访问外层私有成员会在运行时 LinkageError
        assertTrue(names.stream().anyMatch(n -> n.startsWith("com/potatotv/paccclient/fx/Fx$")),
                "嵌套类必须与外层留在同一个包");
        assertFalse(names.stream().anyMatch(n -> n.endsWith("/")), "产物不得写入目录条目");

        String mappingText = Files.readString(mapping, StandardCharsets.UTF_8);
        assertTrue(mappingText.contains("com.potatotv.paccclient.fx.Secret -> com.potatotv.paccclient."),
                "映射表要记录类名对应关系（原名 -> 混淆名）");
        assertFalse(mappingText.contains("com.potatotv.paccclient.fx.Fx -> "),
                "被 keep 的类不进映射表");

        byte[] entryClass = entries.get("com/potatotv/paccclient/fx/Fx.class");
        assertTrue(codeSubAttributes(entryClass).isEmpty() ||
                !codeSubAttributes(entryClass).contains("LineNumberTable"), "行号表必须被剥离");
        assertEquals("SourceFile", sourceFileValue(entryClass), "SourceFile 必须被改名");
    }

    @Test
    void 重命名映射是双射且避开保留下来的成员名() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("work2"));
        Path original = Fixtures.compileAndJar(work, work.resolve("original.jar"));
        Path rulesFile = work.resolve("pob-rules.pob");
        Files.writeString(rulesFile, """
                keep class com.potatotv.paccclient.fx.Fx
                keep class com.potatotv.paccclient.fx.Greeter all
                keep member com.potatotv.paccclient.fx.Fx main
                """, StandardCharsets.UTF_8);

        List<Path> libs = new ArrayList<>();
        JarObfuscator obfuscator = new JarObfuscator(work.resolve("mapping.txt"), "com/potatotv/paccclient");
        obfuscator.run(original, libs, PobRules.parse(rulesFile));

        Renamer renamer = obfuscator.renamer();
        assertNotNull(renamer.classMap().get("com/potatotv/paccclient/fx/Secret"));
        assertEquals(null, renamer.classMap().get("com/potatotv/paccclient/fx/Greeter"), "保留类不得改名");

        Set<String> newMethodNames = new HashSet<>(renamer.methodMap().values());
        assertEquals(renamer.methodMap().size(), newMethodNames.size(), "方法新名必须一一对应");
        assertFalse(newMethodNames.contains("greet"), "被 keep 的成员名不得被复用为新名");
        // 关键：Greeter.greet 被 keep 之后，GreeterImpl.greet 必须一起放弃重命名。
        // 否则接口方法还叫 greet、实现类已改成 a，覆写关系断开，运行时 AbstractMethodError。
        assertFalse(renamer.methodMap().containsKey(
                        Renamer.key("greet", "(Ljava/lang/String;)Ljava/lang/String;")),
                "同一签名只要有一处不能改，该签名整体放弃重命名");
    }

    // ------------------------------------------------------------------

    private static String runMain(Path jar) throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[]{jar.toUri().toURL()},
                ObfuscationTest.class.getClassLoader())) {
            Class<?> entry = Class.forName(Fixtures.ENTRY, true, loader);
            PrintStream saved = System.out;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try {
                System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
                entry.getMethod("main", String[].class).invoke(null, (Object) new String[0]);
            } finally {
                System.setOut(saved);
            }
            return buffer.toString(StandardCharsets.UTF_8).trim();
        }
    }

    private static Map<String, byte[]> read(Path jar) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        return entries;
    }

    private static Set<String> codeSubAttributes(byte[] classBytes) {
        ClassFile cf = ClassFile.read(classBytes);
        Set<String> names = new HashSet<>();
        for (ClassFile.Member method : cf.methods()) {
            for (ClassFile.Attr attr : method.attributes) {
                if (!"Code".equals(cf.utf8(attr.nameIndex))) {
                    continue;
                }
                byte[] info = attr.info;
                int codeLength = (JarObfuscator.u2At(info, 4) << 16) | JarObfuscator.u2At(info, 6);
                int offset = 8 + codeLength;
                int exceptions = JarObfuscator.u2At(info, offset);
                offset += 2 + exceptions * 8;
                int count = JarObfuscator.u2At(info, offset);
                offset += 2;
                for (int i = 0; i < count; i++) {
                    names.add(cf.utf8(JarObfuscator.u2At(info, offset)));
                    int length = (JarObfuscator.u2At(info, offset + 2) << 16)
                            | JarObfuscator.u2At(info, offset + 4);
                    offset += 6 + length;
                }
            }
        }
        return names;
    }

    private static String sourceFileValue(byte[] classBytes) {
        ClassFile cf = ClassFile.read(classBytes);
        for (ClassFile.Attr attr : cf.attributes()) {
            if ("SourceFile".equals(cf.utf8(attr.nameIndex))) {
                return cf.utf8(JarObfuscator.u2At(attr.info, 0));
            }
        }
        return null;
    }
}