package com.potatotv.prl.bytecode;

import com.potatotv.prl.PrlException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 JSON 读写（设计文档 §2.8.1 的 Metadata 段要求 JSON）。
 *
 * <p>模块的第三方依赖是零（{@code maven-enforcer} 会拦），所以自己写一份够用的实现：只支持
 * 对象、数组、字符串、数字、布尔、{@code null} 六种值，不做流式解析、不做转义之外的宽松容错。
 * 元数据本来就是自己写出去再自己读回来，不需要兼容别人的 JSON 方言。</p>
 */
final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------ 写

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, value);
        return sb.toString();
    }

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String text) {
            writeString(sb, text);
        } else if (value instanceof Boolean || value instanceof Number) {
            sb.append(value instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : value);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(entry.getKey()));
                sb.append(':');
                writeValue(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Iterable<?> iterable) {
            sb.append('[');
            boolean first = true;
            for (Object element : iterable) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(sb, element);
            }
            sb.append(']');
        } else {
            writeString(sb, String.valueOf(value));
        }
    }

    private static void writeString(StringBuilder sb, String text) {
        sb.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
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

    // ------------------------------------------------------------------ 读

    static Object read(String text) {
        Reader reader = new Reader(text);
        Object value = reader.readValue();
        reader.skipWhitespace();
        if (!reader.atEnd()) {
            throw new PrlException("JSON 结尾有多余内容，位置 " + reader.pos);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> readObject(String text) {
        Object value = read(text);
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new PrlException("JSON 顶层不是对象");
    }

    private static final class Reader {

        private final String text;
        private int pos;

        Reader(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return pos >= text.length();
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new PrlException("JSON 意外结束");
            }
            char c = text.charAt(pos);
            return switch (c) {
                case '{' -> readMap();
                case '[' -> readList();
                case '"' -> readString();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> readNumber();
            };
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, pos)) {
                throw new PrlException("JSON 期望 " + expected + "，位置 " + pos);
            }
            pos += expected.length();
            return value;
        }

        private Map<String, Object> readMap() {
            pos++;
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == '}') {
                pos++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                expect(':');
                map.put(key, readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new PrlException("JSON 对象没有闭合");
                }
                char c = text.charAt(pos++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new PrlException("JSON 对象里出现意外字符 '" + c + "'");
                }
            }
        }

        private List<Object> readList() {
            pos++;
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && text.charAt(pos) == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new PrlException("JSON 数组没有闭合");
                }
                char c = text.charAt(pos++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new PrlException("JSON 数组里出现意外字符 '" + c + "'");
                }
            }
        }

        private String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (atEnd()) {
                    throw new PrlException("JSON 字符串没有闭合");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char escape = text.charAt(pos++);
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                    default -> throw new PrlException("JSON 非法转义 \\" + escape);
                }
            }
        }

        private Object readNumber() {
            int start = pos;
            while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
                pos++;
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) {
                throw new PrlException("JSON 位置 " + start + " 不是合法值");
            }
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                return Long.parseLong(raw);
            }
            return Double.parseDouble(raw);
        }

        private void expect(char expected) {
            skipWhitespace();
            if (atEnd() || text.charAt(pos) != expected) {
                throw new PrlException("JSON 期望 '" + expected + "'，位置 " + pos);
            }
            pos++;
        }
    }
}