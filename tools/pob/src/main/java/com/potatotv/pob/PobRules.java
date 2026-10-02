package com.potatotv.pob;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code pob-rules.pob} 的解析结果。
 *
 * <pre>
 * # 保留规则（与旧版一致）
 * keep class  &lt;fqcn&gt;                 只保留类名，成员名照常混淆
 * keep class  &lt;fqcn&gt; public          保留类名 + public/protected 成员名
 * keep class  &lt;fqcn&gt; all             保留类名 + 全部成员名
 * keep member &lt;fqcn&gt; &lt;name&gt;          只保留指定名字的成员（不限描述符）
 *
 * # 强化规则
 * enhance class &lt;fqcn&gt;               标记需要强化变换（垃圾代码 / 完整性校验）的类
 *
 * # 开关（key = value）。所有高风险变换默认关闭，保证默认产物与基线一致
 * encrypt_strings = true|false       字符串加密，默认 false
 * encrypt_strings_min_length = N     短于 N 的字符串不加密，默认 4
 * encrypt_strings_keep = "a","b"     白名单，这些字符串不加密，默认空
 * unicode_names = true|false         用 Unicode 私有区字符做短名，默认 false
 * bogus_code = true|false            注入垃圾代码（保守子集），默认 false
 * integrity = true|false             enhance 类注入完整性校验，默认 false
 * flatten = true|false               控制流平坦化（当前未实现，见 JarObfuscator），默认 false
 * </pre>
 */
final class PobRules {

    private static final int LEVEL_NAME_ONLY = 0;
    private static final int LEVEL_PUBLIC = 1;
    private static final int LEVEL_ALL = 2;
    private static final int ACC_PROTECTED = 0x0004;
    private static final int ACC_PUBLIC = 0x0001;

    private final Map<String, Integer> keepClasses = new HashMap<>();
    private final Set<String> keepMembers = new HashSet<>();
    private final Set<String> enhancedClasses = new HashSet<>();

    private boolean encryptStrings;
    private int encryptStringsMinLength = 4;
    private final Set<String> encryptStringsKeep = new LinkedHashSet<>();
    private boolean unicodeNames;
    private boolean bogusCode;
    private boolean integrity;
    private boolean flatten;

    static PobRules parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        PobRules rules = new PobRules();
        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("keep ")) {
                rules.parseKeep(file, i, line);
            } else if (line.startsWith("enhance ")) {
                rules.parseEnhance(file, i, line);
            } else if (line.indexOf('=') > 0) {
                rules.parseOption(file, i, line);
            } else {
                throw new IOException(file + ":" + (i + 1) + " 无法识别的规则：" + line);
            }
        }
        return rules;
    }

    private void parseKeep(Path file, int lineIndex, String line) throws IOException {
        String[] parts = line.split("\\s+");
        if (parts.length < 3) {
            throw new IOException(file + ":" + (lineIndex + 1) + " keep 规则参数不足：" + line);
        }
        String internal = parts[2].replace('.', '/');
        switch (parts[1]) {
            case "class" -> {
                String level = parts.length >= 4 ? parts[3] : "name";
                keepClasses.put(internal, switch (level) {
                    case "name" -> LEVEL_NAME_ONLY;
                    case "public" -> LEVEL_PUBLIC;
                    case "all" -> LEVEL_ALL;
                    default -> throw new IOException(file + ":" + (lineIndex + 1) + " 未知保留级别：" + level);
                });
            }
            case "member" -> {
                if (parts.length < 4) {
                    throw new IOException(file + ":" + (lineIndex + 1) + " keep member 缺少成员名");
                }
                keepMembers.add(parts[2].replace('.', '/') + '\0' + parts[3]);
            }
            default -> throw new IOException(file + ":" + (lineIndex + 1) + " 未知动作：" + parts[1]);
        }
    }

    private void parseEnhance(Path file, int lineIndex, String line) throws IOException {
        String[] parts = line.split("\\s+");
        if (parts.length != 3 || !"class".equals(parts[1])) {
            throw new IOException(file + ":" + (lineIndex + 1) + " enhance 语法应为 'enhance class <fqcn>'：" + line);
        }
        enhancedClasses.add(parts[2].replace('.', '/'));
    }

    private void parseOption(Path file, int lineIndex, String line) throws IOException {
        int eq = line.indexOf('=');
        String key = line.substring(0, eq).trim();
        String value = line.substring(eq + 1).trim();
        switch (key) {
            case "encrypt_strings" -> encryptStrings = bool(file, lineIndex, key, value);
            case "encrypt_strings_min_length" -> {
                try {
                    encryptStringsMinLength = Math.max(0, Integer.parseInt(value));
                } catch (NumberFormatException e) {
                    throw new IOException(file + ":" + (lineIndex + 1) + " 需要整数：" + line);
                }
            }
            case "encrypt_strings_keep" -> encryptStringsKeep.addAll(parseList(value));
            case "unicode_names" -> unicodeNames = bool(file, lineIndex, key, value);
            case "bogus_code" -> bogusCode = bool(file, lineIndex, key, value);
            case "integrity" -> integrity = bool(file, lineIndex, key, value);
            case "flatten" -> flatten = bool(file, lineIndex, key, value);
            default -> throw new IOException(file + ":" + (lineIndex + 1) + " 未知开关：" + key);
        }
    }

    private static boolean bool(Path file, int lineIndex, String key, String value) throws IOException {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IOException(file + ":" + (lineIndex + 1) + " " + key + " 只能是 true/false");
        };
    }

    /** 解析 {@code "a","b"} 或 {@code a,b} 形态的字符串列表。 */
    private static List<String> parseList(String value) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == ',' && !quoted) {
                addToken(out, current);
            } else {
                current.append(c);
            }
        }
        addToken(out, current);
        return out;
    }

    private static void addToken(List<String> out, StringBuilder current) {
        String token = current.toString().trim();
        if (!token.isEmpty()) {
            out.add(token);
        }
        current.setLength(0);
    }

    /** 剥注释：# 只在引号之外才当注释起始，否则 encrypt_strings_keep = "a#b" 会被截断。 */
    private static String stripComment(String line) {
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (c == '#' && !quoted) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    boolean keepsClass(String internalName) {
        return keepClasses.containsKey(internalName);
    }

    Set<String> keptClassNames() {
        return keepClasses.keySet();
    }

    boolean enhancesClass(String internalName) {
        return enhancedClasses.contains(internalName);
    }

    boolean encryptStrings() {
        return encryptStrings;
    }

    int encryptStringsMinLength() {
        return encryptStringsMinLength;
    }

    Set<String> encryptStringsKeep() {
        return encryptStringsKeep;
    }

    boolean unicodeNames() {
        return unicodeNames;
    }

    boolean bogusCode() {
        return bogusCode;
    }

    boolean integrity() {
        return integrity;
    }

    boolean flatten() {
        return flatten;
    }

    /** 类名被保留、且成员级别覆盖到该成员时为 true——这类成员名必须原样留下。 */
    boolean keepsMember(String owner, String memberName, int access) {
        if (keepMembers.contains(owner + '\0' + memberName)) {
            return true;
        }
        Integer level = keepClasses.get(owner);
        if (level == null || level == LEVEL_NAME_ONLY) {
            return false;
        }
        if (level == LEVEL_ALL) {
            return true;
        }
        return (access & (ACC_PUBLIC | ACC_PROTECTED)) != 0;
    }
}