package com.potatotv.pob;

import java.util.Map;

/**
 * 常量池 UTF-8 内容的重写规则。
 *
 * <p>一个 UTF-8 条目在 class 文件里可能被同时当作类名（CONSTANT_Class）、
 * 描述符片段（NameAndType / MethodType）、泛型签名（Signature 属性）、
 * 注解类型，以及字符串常量。按「内容」而不是「引用位置」来改写，
 * 正好一次覆盖全部用法，也天然等价于 ProGuard 的 {@code -adaptclassstrings}：
 * 代码里形如 {@code Class.forName("com.potatotv.paccclient.Xxx")} 的字符串会同步换成新类名。</p>
 */
final class NameRewriter {

    private final Map<String, String> classMap;

    NameRewriter(Map<String, String> classMap) {
        this.classMap = classMap;
    }

    /**
     * 改写一条 UTF-8 常量：
     * <ol>
     *   <li>整串就是一个被重命名的内部类名 → 换成新类名（覆盖 CONSTANT_Class 与字符串常量）；</li>
     *   <li>整串是被重命名类的点分名 → 换成点分新名（覆盖 {@code Class.forName} 的实参）；</li>
     *   <li>其余按描述符/签名扫描，逐段替换 {@code L<类名>;}。</li>
     * </ol>
     */
    String rewrite(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        String direct = classMap.get(value);
        if (direct != null) {
            return direct;
        }
        if (value.indexOf('.') >= 0 && value.indexOf('/') < 0) {
            String slashed = value.replace('.', '/');
            String mapped = classMap.get(slashed);
            if (mapped != null) {
                return mapped.replace('/', '.');
            }
        }
        return rewriteTypes(value);
    }

    /**
     * 扫描并替换描述符/签名里的类型引用。识别边界遵循 JVMS 4.3 与 4.7.9.1：
     * 只有出现在 {@code L} 之后、且 {@code L} 前一个字符是类型边界（或串首）时才当作类名。
     */
    private String rewriteTypes(String s) {
        if (s.indexOf('L') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == 'L' && isTypeBoundary(i == 0 ? '\0' : s.charAt(i - 1))) {
                int end = i + 1;
                while (end < s.length()) {
                    char e = s.charAt(end);
                    // '.' 不能当终止符：泛型签名里嵌套类写作 Lcom/foo/Outer.Inner;，
                    // 它是类型名的一部分，截断后只剩外层类名，内层就查不到了。
                    if (e == ';' || e == '<') {
                        break;
                    }
                    end++;
                }
                String raw = s.substring(i + 1, end);
                String mapped = lookup(raw);
                out.append('L');
                out.append(mapped != null ? mapped : raw);
                i = end;
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** 泛型签名里嵌套类写作 {@code Outer.Inner}，类名里写作 {@code Outer$Inner}，两种都认。 */
    private String lookup(String raw) {
        String mapped = classMap.get(raw);
        if (mapped != null) {
            return mapped;
        }
        if (raw.indexOf('.') >= 0) {
            return classMap.get(raw.replace('.', '$'));
        }
        return null;
    }

    private static boolean isTypeBoundary(char c) {
        return c == '\0' || c == '(' || c == ')' || c == ';' || c == '[' || c == '<'
                || c == '>' || c == '+' || c == '-' || c == '*' || c == '^';
    }

    /**
     * 取方法描述符的返回类型内部名；返回基本类型/数组/非法描述符时为 null。
     * 用途：{@code invokedynamic} 的 SAM 名字要从返回的函数式接口反查。
     */
    static String returnTypeOf(String methodDescriptor) {
        if (methodDescriptor == null || methodDescriptor.isEmpty() || methodDescriptor.charAt(0) != '(') {
            return null;
        }
        int depth = 0;
        for (int i = 0; i < methodDescriptor.length(); i++) {
            char c = methodDescriptor.charAt(i);
            if (c == '(') {
                depth++;
                continue;
            }
            if (c == ')') {
                depth--;
                if (depth == 0 && i + 1 < methodDescriptor.length()) {
                    String rest = methodDescriptor.substring(i + 1);
                    if (rest.length() >= 2 && rest.charAt(0) == 'L' && rest.endsWith(";")) {
                        return rest.substring(1, rest.length() - 1);
                    }
                    return null;
                }
            }
        }
        return null;
    }
}