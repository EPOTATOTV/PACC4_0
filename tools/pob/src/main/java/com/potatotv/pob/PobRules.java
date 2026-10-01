package com.potatotv.pob;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code pob-rules.pob} 的解析结果。
 *
 * <pre>
 * # 注释
 * keep class  &lt;fqcn&gt;                 只保留类名，成员名照常混淆
 * keep class  &lt;fqcn&gt; public          保留类名 + public/protected 成员名
 * keep class  &lt;fqcn&gt; all             保留类名 + 全部成员名
 * keep member &lt;fqcn&gt; &lt;name&gt;          只保留指定名字的成员（不限描述符）
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

    static PobRules parse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        PobRules rules = new PobRules();
        for (int i = 0; i < lines.size(); i++) {
            String line = stripComment(lines.get(i)).trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            if (parts.length < 3 || !"keep".equals(parts[0])) {
                throw new IOException(file + ":" + (i + 1) + " 无法识别的规则：" + line);
            }
            String internal = parts[2].replace('.', '/');
            switch (parts[1]) {
                case "class" -> {
                    String level = parts.length >= 4 ? parts[3] : "name";
                    rules.keepClasses.put(internal, switch (level) {
                        case "name" -> LEVEL_NAME_ONLY;
                        case "public" -> LEVEL_PUBLIC;
                        case "all" -> LEVEL_ALL;
                        default -> throw new IOException(file + ":" + (i + 1) + " 未知保留级别：" + level);
                    });
                }
                case "member" -> {
                    if (parts.length < 4) {
                        throw new IOException(file + ":" + (i + 1) + " keep member 缺少成员名");
                    }
                    rules.keepMembers.add(parts[2].replace('.', '/') + '\0' + parts[3]);
                }
                default -> throw new IOException(file + ":" + (i + 1) + " 未知动作：" + parts[1]);
            }
        }
        return rules;
    }

    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        return hash < 0 ? line : line.substring(0, hash);
    }

    boolean keepsClass(String internalName) {
        return keepClasses.containsKey(internalName);
    }

    Set<String> keptClassNames() {
        return keepClasses.keySet();
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