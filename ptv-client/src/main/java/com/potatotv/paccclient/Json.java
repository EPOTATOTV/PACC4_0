package com.potatotv.paccclient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 编/解码器（避免引入第三方依赖，仅覆盖本项目所需结构）。
 * <p>编码见 {@link #encode}；解码采用递归下降，覆盖对象/数组/字符串/数字/布尔/null，
 * 供客户端解析特征库增量、远程配置等服务端下发的扁平结构。</p>
 */
public final class Json {

    private Json() {
    }

    public static String encode(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    public static String encode(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(encode(e.getKey())).append(':');
            Object v = e.getValue();
            if (v instanceof String str) sb.append(encode(str));
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v == null) sb.append("null");
            else sb.append(encode(String.valueOf(v)));
        }
        return sb.append('}').toString();
    }

    /** 解析完整 JSON 文档，返回 Object（Map / List / String / Number / Boolean / null）。 */
    public static Object decode(String json) {
        if (json == null) return null;
        Parser p = new Parser(json);
        Object v = p.parseValue();
        p.skipWs();
        if (p.pos < p.s.length()) throw new IllegalArgumentException("JSON 末尾存在多余字符");
        return v;
    }

    /** 便捷解析为对象（顶层必须是 {}，否则抛异常）。 */
    public static Map<String, Object> decodeObject(String json) {
        Object v = decode(json);
        if (!(v instanceof Map<?, ?> m)) throw new IllegalArgumentException("期望 JSON 对象");
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m;
        return out;
    }

    /** 极简递归下降解析器。 */
    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
            this.pos = 0;
        }

        Object parseValue() {
            skipWs();
            if (pos >= s.length()) throw new IllegalArgumentException("JSON 意外结束");
            char c = s.charAt(pos);
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

        Map<String, Object> parseObject() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue();
                m.put(key, value);
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return m;
                } else {
                    throw new IllegalArgumentException("JSON 对象期望 ',' 或 '}'，实际=" + c);
                }
            }
        }

        List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("JSON 数组期望 ',' 或 ']'，实际=" + c);
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (pos >= s.length()) throw new IllegalArgumentException("JSON 字符串尾随反斜杠");
                    char esc = s.charAt(pos++);
                    sb.append(switch (esc) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> parseUnicode();
                        default -> throw new IllegalArgumentException("未知转义 \\" + esc);
                    });
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 字符串未闭合");
        }

        private char parseUnicode() {
            if (pos + 4 > s.length()) throw new IllegalArgumentException("JSON \\u 转义长度不足");
            String hex = s.substring(pos, pos + 4);
            pos += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        Object parseLiteral(String lit, Object val) {
            if (s.regionMatches(pos, lit, 0, lit.length())) {
                pos += lit.length();
                return val;
            }
            throw new IllegalArgumentException("非法字面量@" + pos);
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') pos++;
            while (pos < s.length() && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '.' || s.charAt(pos) == 'e' || s.charAt(pos) == 'E' || s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) throw new IllegalArgumentException("非法数字@" + start);
            if (num.contains(".") || num.contains("e") || num.contains("E")) return Double.parseDouble(num);
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }

        private void expect(char c) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new IllegalArgumentException("期望 '" + c + "'@" + pos + " 实际=" + peek());
            }
            pos++;
        }

        private char peek() {
            skipWs();
            if (pos >= s.length()) return '\0';
            return s.charAt(pos);
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }
    }
}