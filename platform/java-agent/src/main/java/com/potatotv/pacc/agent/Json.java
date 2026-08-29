package com.potatotv.pacc.agent;

import java.util.Collection;
import java.util.Map;

/**
 * 极简 JSON 编码器（零第三方依赖）。
 */
public final class Json {

    private Json() {
    }

    public static String encode(Object root) {
        StringBuilder sb = new StringBuilder();
        write(root, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(Object v, StringBuilder sb) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append('"').append(escape(s)).append('"');
        } else if (v instanceof Boolean || v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<String, Object>) map).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                write(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb);
            }
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object o : c) {
                if (!first) sb.append(',');
                first = false;
                write(o, sb);
            }
            sb.append(']');
        } else {
            write(String.valueOf(v), sb);
        }
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
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
        return sb.toString();
    }
}