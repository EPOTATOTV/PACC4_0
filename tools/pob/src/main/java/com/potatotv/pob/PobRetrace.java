package com.potatotv.pob;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * POB 崩溃堆栈还原工具：读 {@code pob-mapping.txt}，把混淆后的类名 / 方法名换回原名。
 *
 * <p>mapping 文件由 {@link JarObfuscator#writeMapping()} 输出，方向是「原名 -&gt; 混淆名」，
 * 本工具内部反向成「混淆名 -&gt; 原名」。类名是点分类名，成员名只有短名（全局唯一分配），
 * 因此可以各自建一张反查表。</p>
 *
 * <p>堆栈里方法名是裸短名，直接全文替换会把正文里的字母也换掉，所以成员名只在
 * {@code at <类>.<方法>(...)} 形态的帧行里替换；类名则按两侧词边界做全文替换，
 * 覆盖 {@code at} 帧与 {@code Caused by:} 里的异常类型。</p>
 *
 * <p>用法：</p>
 * <pre>
 * java -cp pacc-obfuscator.jar com.potatotv.pob.PobRetrace &lt;mapping&gt; [堆栈文件]
 * </pre>
 * <p>省略堆栈文件时从标准输入读，还原结果写到标准输出。行号无法还原——POB 不保留
 * 行号表，SourceFile 统一写成 {@code SourceFile}，映射表里也没有行号信息。</p>
 */
public final class PobRetrace {

    /** at 帧：at &lt;类&gt;.&lt;方法&gt;(&lt;来源&gt;)。类名与方法名都可能含 Unicode 私有区字符。 */
    private static final Pattern FRAME = Pattern.compile("^(\\s*at\\s+)(.+)\\.([^.(]+)(\\(.*)$");

    /** 词边界：ASCII 标识符字符 + 声明的 Unicode 私有区，避免把长名截成前缀。 */
    private static final String ID_CHARS = "A-Za-z0-9_$\\uE000-\\uF8FF";

    private final Map<String, String> classMap = new LinkedHashMap<>();
    private final Map<String, String> memberMap = new LinkedHashMap<>();

    private PobRetrace(Map<String, String> classMap, Map<String, String> memberMap) {
        this.classMap.putAll(classMap);
        this.memberMap.putAll(memberMap);
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("用法: PobRetrace <mapping> [stacktrace-file]");
            System.exit(2);
        }
        PobRetrace retrace = parse(Path.of(args[0]));
        String stacktrace = args.length == 2
                ? Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)
                : new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        System.out.println(retrace.retrace(stacktrace));
    }

    /** 解析 mapping 文件；方向统一成「混淆名 -&gt; 原名」。 */
    static PobRetrace parse(Path mapping) throws IOException {
        Map<String, String> classes = new LinkedHashMap<>();
        Map<String, String> members = new LinkedHashMap<>();
        for (String raw : Files.readAllLines(mapping, StandardCharsets.UTF_8)) {
            if (raw.isEmpty() || raw.startsWith("#")) {
                continue;
            }
            int arrow = raw.lastIndexOf(" -> ");
            if (arrow < 0) {
                continue;
            }
            String left = raw.substring(0, arrow);
            String right = raw.substring(arrow + 4).trim();
            if (raw.startsWith(" ") || raw.startsWith("\t")) {
                // 成员行：  <原名> <描述符> -> <混淆名>
                String[] parts = left.trim().split("\\s+");
                if (parts.length >= 1 && !right.isEmpty()) {
                    members.put(right, parts[0]);
                }
            } else if (!right.isEmpty()) {
                classes.put(right, left.trim());
            }
        }
        return new PobRetrace(classes, members);
    }

    /** 还原一段堆栈文本。 */
    public String retrace(String stacktrace) {
        if (stacktrace == null || stacktrace.isEmpty()) {
            return stacktrace;
        }
        String[] lines = stacktrace.split("\n", -1);
        StringBuilder out = new StringBuilder(stacktrace.length() + 64);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String line = lines[i];
            // 帧行里的裸成员名先换：此时类名还是混淆名，帧结构没被类名替换破坏
            Matcher frame = FRAME.matcher(line);
            if (frame.matches()) {
                String method = memberMap.getOrDefault(frame.group(3), frame.group(3));
                line = frame.group(1) + frame.group(2) + "." + method + frame.group(4);
            }
            out.append(retraceClassNames(line));
        }
        return out.toString();
    }

    /** 长名优先，两侧词边界对齐后做全文替换，覆盖异常头与帧里剩余的类名。 */
    private String retraceClassNames(String line) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(classMap.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        String result = line;
        for (Map.Entry<String, String> e : entries) {
            if (result.contains(e.getKey())) {
                result = result.replaceAll(
                        "(?<![" + ID_CHARS + "])" + Pattern.quote(e.getKey()) + "(?![" + ID_CHARS + "])",
                        Matcher.quoteReplacement(e.getValue()));
            }
        }
        return result;
    }
}
