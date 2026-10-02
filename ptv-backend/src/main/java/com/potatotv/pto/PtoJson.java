package com.potatotv.pto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PTO 内嵌的极简 JSON 编解码器。
 *
 * <p>JWT 的 header / payload 结构很窄：一层对象，值为字符串、数字、布尔、null，
 * 或者字符串数组（{@code aud}）。为了不把 Jackson/Gson 引回来（那等于重新背上 PTO 想甩掉的依赖），
 * 这里手写一个够用的子集：对象、数组、字符串、整数、浮点、布尔、null。</p>
 *
 * <p>只解析自己签发或 jjwt 签发的标准载荷，不追求成为通用 JSON 库。</p>
 */
final class PtoJson {

    private PtoJson() {
    }

    // ------------------------------ 编码 ------------------------------

    static String write(Object value) {
        StringBuilder sb = new StringBuilder(128);
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b.booleanValue());
        } else if (value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte) {
            sb.append(((Number) value).longValue());
        } else if (value instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new PtoException("JSON 不支持的数字：" + d);
            }
            sb.append(d);
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map);
        } else if (value instanceof Iterable<?> it) {
            writeArray(sb, it);
        } else {
            writeString(sb, value.toString());
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            // null 值没有承载意义（JWT 里缺失即等价），直接省略，避免产出无用的 "key":null
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, String.valueOf(e.getKey()));
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> it) {
        sb.append('[');
        boolean first = true;
        for (Object o : it) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, o);
        }
        sb.append(']');
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    // ------------------------------ 解码 ------------------------------

    /** 解析顶层对象。顶层不是对象、或尾部有残留内容即视为非法。 */
    static Map<String, Object> readObject(String json) {
        Parser parser = new Parser(json);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new PtoException("JSON 尾部存在残留内容");
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new PtoException("JSON 顶层不是对象");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            out.put(String.valueOf(e.getKey()), e.getValue());
        }
        return out;
    }

    private static final class Parser {

        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        boolean atEnd() {
            return i >= s.length();
        }

        void skipWhitespace() {
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    i++;
                } else {
                    break;
                }
            }
        }

        Object parseValue() {
            skipWhitespace();
            char c = peek();
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                map.put(key, parseValue());
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    return map;
                }
                throw new PtoException("JSON 对象格式错误");
            }
        }

        private List<Object> parseArray() {
            expect('[');
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (peek() == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    return list;
                }
                throw new PtoException("JSON 数组格式错误");
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new PtoException("JSON 字符串未闭合");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (atEnd()) {
                    throw new PtoException("JSON 转义未完成");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw new PtoException("JSON \\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw new PtoException("JSON 非法转义：\\" + e);
                }
            }
        }

        private Object parseNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            boolean floating = false;
            while (!atEnd()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    i++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    floating = true;
                    i++;
                } else {
                    break;
                }
            }
            String num = s.substring(start, i);
            if (num.isEmpty()) {
                throw new PtoException("JSON 非法数字");
            }
            try {
                return floating ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
            } catch (NumberFormatException ex) {
                throw new PtoException("JSON 非法数字：" + num, ex);
            }
        }

        private Object parseLiteral(String literal, Object value) {
            if (!s.startsWith(literal, i)) {
                throw new PtoException("JSON 非法字面量");
            }
            i += literal.length();
            return value;
        }

        private char peek() {
            if (atEnd()) {
                throw new PtoException("JSON 意外结束");
            }
            return s.charAt(i);
        }

        private char next() {
            if (atEnd()) {
                throw new PtoException("JSON 意外结束");
            }
            return s.charAt(i++);
        }

        private void expect(char c) {
            if (atEnd() || s.charAt(i) != c) {
                throw new PtoException("JSON 期望字符 '" + c + "'");
            }
            i++;
        }
    }
}
