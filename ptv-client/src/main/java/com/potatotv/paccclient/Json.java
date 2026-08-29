package com.potatotv.paccclient;

import java.util.Map;

/**
 * 极简 JSON 编码器（避免引入第三方依赖，仅覆盖本项目所需结构）。
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
}