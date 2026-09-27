package com.potatotv.pcu;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 读写：PCU 核心要零第三方依赖，而更新接口只有「检查」与「上报」两个报文，
 * 不值得为它引入 Jackson/Gson，也不该把协议运行时（PBP）拖进来。
 *
 * <p>解析结果用最朴素的类型表示：对象 {@code Map<String,Object>}、数组 {@code List<Object>}、
 * 数字 {@code Long}/{@code Double}、其余 {@code String}/{@code Boolean}/null。
 * 只实现 RFC 8259 的必需部分，不做流式解析——更新报文是 KB 级。</p>
 */
public final class PcuJson {

    private final String src;
    private int pos;

    private PcuJson(String src) {
        this.src = src;
    }

    /** 解析一段 JSON 文本，返回 Map/List/String/Long/Double/Boolean/null。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new PcuException("JSON 文本为空");
        }
        PcuJson p = new PcuJson(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        if (p.pos != p.src.length()) {
            throw new PcuException("JSON 解析结束后仍有残余字符，位置 " + p.pos);
        }
        return value;
    }

    /** 序列化：Map/List/Number/String/Boolean/null，其它类型一律报错而不是静默 toString。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(sb, value);
        return sb.toString();
    }

    /** 取对象字段，非对象或字段缺失返回 null。 */
    public static Object field(Object json, String name) {
        return json instanceof Map<?, ?> map ? map.get(name) : null;
    }

    /** 取字符串字段；数字/布尔会转成字符串，缺失返回 null。 */
    public static String str(Object json, String name) {
        Object v = field(json, name);
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return null;
    }

    /** 取 long 字段；缺失或类型不符返回 def。 */
    public static long num(Object json, String name, long def) {
        Object v = field(json, name);
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    /** 取 boolean 字段；只有真正的 JSON 布尔与 "true"/"false" 字符串算数。 */
    public static boolean bool(Object json, String name, boolean def) {
        Object v = field(json, name);
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof String s) {
            if ("true".equalsIgnoreCase(s.trim())) {
                return true;
            }
            if ("false".equalsIgnoreCase(s.trim())) {
                return false;
            }
        }
        return def;
    }

    /** 取子对象，类型不符返回 null。 */
    public static Map<String, Object> object(Object json, String name) {
        Object v = field(json, name);
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return null;
    }

    // ------------------------------------------------------------------ 解析

    private void skipWhitespace() {
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (pos >= src.length()) {
            throw new PcuException("JSON 意外结束，位置 " + pos);
        }
        return src.charAt(pos);
    }

    private Object readValue() {
        char c = peek();
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw new PcuException("JSON 对象键必须是字符串，位置 " + pos);
            }
            String key = readString();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            Object value = readValue();
            // 重复键取后者：与主流实现一致，且更新报文没有重复键的场景
            map.put(key, value);
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == '}') {
                pos++;
                return map;
            }
            throw new PcuException("JSON 对象缺少 ',' 或 '}'，位置 " + pos);
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
                continue;
            }
            if (c == ']') {
                pos++;
                return list;
            }
            throw new PcuException("JSON 数组缺少 ',' 或 ']'，位置 " + pos);
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (!src.startsWith(literal, pos)) {
            throw new PcuException("JSON 非法字面量，位置 " + pos);
        }
        pos += literal.length();
        return value;
    }

    private Number readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        boolean fraction = false;
        while (pos < src.length()) {
            char c = src.charAt(pos);
            if (c >= '0' && c <= '9') {
                pos++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                // '+'/'-'/'e' 只可能出现在指数部分，出现即说明是浮点数
                if (c != '.') {
                    fraction = true;
                }
                pos++;
            } else {
                break;
            }
        }
        String raw = src.substring(start, pos);
        if (raw.isEmpty() || "-".equals(raw)) {
            throw new PcuException("JSON 非法数字，位置 " + start);
        }
        try {
            if (!fraction) {
                return Long.parseLong(raw);
            }
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            throw new PcuException("JSON 数字无法解析：" + raw, e);
        }
    }

    private String readString() {
        expect('"');
        StringBuilder sb = new StringBuilder(32);
        while (true) {
            if (pos >= src.length()) {
                throw new PcuException("JSON 字符串未闭合");
            }
            char c = src.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                throw new PcuException("JSON 转义序列不完整");
            }
            char esc = src.charAt(pos++);
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> sb.append(readUnicodeEscape());
                default -> throw new PcuException("JSON 未知转义 \\" + esc);
            }
        }
    }

    private char readUnicodeEscape() {
        if (pos + 4 > src.length()) {
            throw new PcuException("JSON \\u 转义不足 4 位");
        }
        String hex = src.substring(pos, pos + 4);
        pos += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException e) {
            throw new PcuException("JSON \\u 转义非法：" + hex, e);
        }
    }

    private void expect(char c) {
        if (pos >= src.length() || src.charAt(pos) != c) {
            throw new PcuException("JSON 期望 '" + c + "'，位置 " + pos);
        }
        pos++;
    }

    // ------------------------------------------------------------------ 序列化

    private static void writeValue(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String s) {
            writeString(sb, s);
        } else if (value instanceof Boolean b) {
            sb.append(b);
        } else if (value instanceof Double d) {
            writeDouble(sb, d);
        } else if (value instanceof Float f) {
            writeDouble(sb, f.doubleValue());
        } else if (value instanceof Number n) {
            sb.append(n.longValue());
        } else if (value instanceof Map<?, ?> map) {
            writeObject(sb, map);
        } else if (value instanceof Iterable<?> it) {
            writeArray(sb, it);
        } else {
            throw new PcuException("PcuJson 不支持的类型：" + value.getClass().getName());
        }
    }

    private static void writeDouble(StringBuilder sb, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new PcuException("JSON 不允许写入 NaN/Infinity");
        }
        sb.append(d);
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : map.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                throw new PcuException("PcuJson 对象的键必须是 String");
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(sb, key);
            sb.append(':');
            writeValue(sb, e.getValue());
        }
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, Iterable<?> values) {
        sb.append('[');
        boolean first = true;
        for (Object v : values) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeValue(sb, v);
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
}