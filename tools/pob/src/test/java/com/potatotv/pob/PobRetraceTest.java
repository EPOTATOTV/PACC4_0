package com.potatotv.pob;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 崩溃栈还原：mapping（原名 -&gt; 混淆名）反查，类名全文替换、成员名只在 at 帧里替换。
 *
 * <p>混淆名用 {@code unicode_names} 下的私有区字符，与发行产物一致。</p>
 */
class PobRetraceTest {

    private static final String OBF_A = "com.potatotv.paccclient.\uE000";
    private static final String OBF_B = "com.potatotv.paccclient.\uE001";

    private static String mappingText() {
        return String.join("\n",
                "# PACC POB mapping（原名 -> 混淆名，仅供崩溃栈还原，不得随发行包发布）",
                "com.potatotv.paccclient.detection.DetectionEngine -> " + OBF_A,
                "com.potatotv.paccclient.security.HookDetector -> " + OBF_B,
                "com.potatotv.paccclient.PaccClient -> com.potatotv.paccclient.PaccClient",
                "",
                "# 成员（方法）：原名 + 描述符 -> 混淆名",
                "  scan ()V -> a",
                "  check (Ljava/lang/String;)Z -> b",
                "",
                "# 成员（字段）：原名 + 描述符 -> 混淆名",
                "  version Ljava/lang/String; -> c",
                "");
    }

    private PobRetrace load(Path dir) throws IOException {
        Path mapping = dir.resolve("pob-mapping.txt");
        Files.writeString(mapping, mappingText(), StandardCharsets.UTF_8);
        return PobRetrace.parse(mapping);
    }

    @Test
    void at帧里的类名与方法名一起还原(@TempDir Path dir) throws IOException {
        PobRetrace retrace = load(dir);
        String out = retrace.retrace("\tat " + OBF_A + ".a(PaccClient.java:42)\n");
        assertEquals("\tat com.potatotv.paccclient.detection.DetectionEngine.scan(PaccClient.java:42)\n", out);
    }

    @Test
    void 异常头里的类名也还原(@TempDir Path dir) throws IOException {
        PobRetrace retrace = load(dir);
        String out = retrace.retrace("Caused by: " + OBF_B + ": boom\n");
        assertEquals("Caused by: com.potatotv.paccclient.security.HookDetector: boom\n", out);
    }

    @Test
    void 保留类名不被替换(@TempDir Path dir) throws IOException {
        PobRetrace retrace = load(dir);
        String out = retrace.retrace("\tat com.potatotv.paccclient.PaccClient.main(PaccClient.java:1)\n");
        assertEquals("\tat com.potatotv.paccclient.PaccClient.main(PaccClient.java:1)\n", out);
    }

    @Test
    void 非帧行里的裸短名不被误伤(@TempDir Path dir) throws IOException {
        // 消息正文里的 “a” 恰好与某个混淆成员名相同，但它不在 at 帧里，绝不能替换
        PobRetrace retrace = load(dir);
        String out = retrace.retrace("java.lang.RuntimeException: a value named b here\n");
        assertEquals("java.lang.RuntimeException: a value named b here\n", out);
    }

    @Test
    void 未知成员名原样保留(@TempDir Path dir) throws IOException {
        PobRetrace retrace = load(dir);
        String out = retrace.retrace("\tat " + OBF_A + ".notMapped(PaccClient.java:9)\n");
        assertEquals("\tat com.potatotv.paccclient.detection.DetectionEngine.notMapped(PaccClient.java:9)\n", out);
    }

    @Test
    void 多帧与空行保留原结构(@TempDir Path dir) throws IOException {
        PobRetrace retrace = load(dir);
        String trace = String.join("\n", List.of(
                "java.lang.RuntimeException: boom",
                "\tat " + OBF_A + ".a(PaccClient.java:1)",
                "",
                "\tat " + OBF_B + ".b(PaccClient.java:2)")) + "\n";
        String expected = String.join("\n", List.of(
                "java.lang.RuntimeException: boom",
                "\tat com.potatotv.paccclient.detection.DetectionEngine.scan(PaccClient.java:1)",
                "",
                "\tat com.potatotv.paccclient.security.HookDetector.check(PaccClient.java:2)")) + "\n";
        assertEquals(expected, retrace.retrace(trace));
    }

    @Test
    void 长类名优先避免前缀截断(@TempDir Path dir) throws IOException {
        // 点号分隔时短名可成为长名的前缀：不按长度优先，就会先把前缀换成短名原件而拆坏长名
        Path mapping = dir.resolve("m.txt");
        Files.writeString(mapping, String.join("\n",
                "p.OrigLong -> x.y.Z.Inner",
                "p.OrigShort -> x.y.Z") + "\n", StandardCharsets.UTF_8);
        PobRetrace retrace = PobRetrace.parse(mapping);
        assertEquals("p.OrigLong", retrace.retrace("x.y.Z.Inner"));
    }

    @Test
    void 空输入原样返回(@TempDir Path dir) throws IOException {
        PobRetrace retrace = load(dir);
        assertEquals("", retrace.retrace(""));
    }
}
