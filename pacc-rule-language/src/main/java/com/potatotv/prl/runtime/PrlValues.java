package com.potatotv.prl.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PRL 的运行时值模型。
 *
 * <p>值一律是普通 JDK 对象，没有自己的包装类型，理由有二：宿主（PACC）在 §2.11.2 里直接收发
 * {@code Object}、{@code List<?>}、{@code Map<?, ?>}，包一层壳只会让每个宿主函数都要拆包；
 * 规则语言的类型正确性已经在编译期保证（§2.3.5），运行时不需要靠类型标记去兜底。</p>
 *
 * <table>
 *   <caption>类型映射</caption>
 *   <tr><td>{@code int}</td><td>{@link Long}</td></tr>
 *   <tr><td>{@code float}</td><td>{@link Double}</td></tr>
 *   <tr><td>{@code bool}</td><td>{@link Boolean}</td></tr>
 *   <tr><td>{@code string}</td><td>{@link String}</td></tr>
 *   <tr><td>{@code null}</td><td>{@code null}</td></tr>
 *   <tr><td>{@code list[T]}</td><td>{@link List}（{@link java.util.ArrayList}）</td></tr>
 *   <tr><td>{@code set[T]}</td><td>{@link Set}（{@link java.util.LinkedHashSet}）</td></tr>
 *   <tr><td>{@code map[K,V]}</td><td>{@link Map}（{@link java.util.LinkedHashMap}，保持插入顺序）</td></tr>
 *   <tr><td>{@code tuple[...]}</td><td>{@link List}（定长，与 list 同表示）</td></tr>
 *   <tr><td>宿主上下文类型</td><td>宿主自己注册的 Java 对象</td></tr>
 *   <tr><td>函数类型</td><td>{@link PrlCallable}</td></tr>
 * </table>
 *
 * <p>本类只做读取/比较/渲染，不负责创建集合时的上限校验 —— 那是沙箱（§2.11.1 的 L2）的事。</p>
 */
public final class PrlValues {

    private PrlValues() {
    }

    // ------------------------------------------------------------------ 类型判定

    public static boolean isNumber(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Double
                || value instanceof Float || value instanceof Short || value instanceof Byte;
    }

    public static boolean isInteger(Object value) {
        return value instanceof Long || value instanceof Integer || value instanceof Short
                || value instanceof Byte;
    }

    /** 值对应的 PRL 类型名，用于运行时错误信息。 */
    public static String typeName(Object value) {
        if (value == null) {
            return "null";
        }
        if (isInteger(value)) {
            return "int";
        }
        if (isNumber(value)) {
            return "float";
        }
        if (value instanceof Boolean) {
            return "bool";
        }
        if (value instanceof String) {
            return "string";
        }
        if (value instanceof Map) {
            return "map";
        }
        if (value instanceof Set) {
            return "set";
        }
        if (value instanceof List) {
            return "list";
        }
        if (value instanceof PrlCallable) {
            return "函数";
        }
        return value.getClass().getSimpleName();
    }

    // ------------------------------------------------------------------ 取值

    public static long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new PrlExecutionException("期望 int，实际为 " + describe(value));
    }

    public static double asDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        throw new PrlExecutionException("期望 float，实际为 " + describe(value));
    }

    public static boolean asBool(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        throw new PrlExecutionException("期望 bool，实际为 " + describe(value));
    }

    public static String asString(Object value) {
        if (value instanceof String text) {
            return text;
        }
        throw new PrlExecutionException("期望 string，实际为 " + describe(value));
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asList(Object value) {
        if (value instanceof List<?> list) {
            return (List<Object>) list;
        }
        throw new PrlExecutionException("期望 list，实际为 " + describe(value));
    }

    @SuppressWarnings("unchecked")
    public static Map<Object, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<Object, Object>) map;
        }
        throw new PrlExecutionException("期望 map，实际为 " + describe(value));
    }

    @SuppressWarnings("unchecked")
    public static Set<Object> asSet(Object value) {
        if (value instanceof Set<?> set) {
            return (Set<Object>) set;
        }
        throw new PrlExecutionException("期望 set，实际为 " + describe(value));
    }

    public static PrlCallable asCallable(Object value) {
        if (value instanceof PrlCallable callable) {
            return callable;
        }
        throw new PrlExecutionException("期望函数，实际为 " + describe(value));
    }

    /** 集合或字符串的长度。 */
    public static int length(Object value) {
        if (value instanceof String text) {
            return text.length();
        }
        if (value instanceof Map<?, ?> map) {
            return map.size();
        }
        if (value instanceof Collection<?> collection) {
            return collection.size();
        }
        throw new PrlExecutionException("取长度需要 string/list/set/map，实际为 " + describe(value));
    }

    // ------------------------------------------------------------------ 相等与比较

    /**
     * {@code ==} 的语义。
     *
     * <p>数值按数学值比较（{@code 1} 与 {@code 1.0} 相等）—— 编译期已经禁止 int 与 float 混算，
     * 这里只是在宿主返回了 {@link Integer} 之类窄包装时不让比较出意外结果。</p>
     */
    public static boolean equalsValue(Object left, Object right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        if (isNumber(left) && isNumber(right)) {
            return Double.compare(asDouble(left), asDouble(right)) == 0;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!equalsValue(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Map<?, ?> a && right instanceof Map<?, ?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (Map.Entry<?, ?> entry : a.entrySet()) {
                if (!b.containsKey(entry.getKey()) || !equalsValue(entry.getValue(), b.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (left instanceof Set<?> a && right instanceof Set<?> b) {
            return a.size() == b.size() && a.containsAll(b);
        }
        return left.equals(right);
    }

    /**
     * 排序/比较语义。
     *
     * @throws PrlExecutionException 两侧类型不可比较
     */
    public static int compare(Object left, Object right) {
        if (left == null && right == null) {
            return 0;
        }
        if (left == null) {
            return -1;
        }
        if (right == null) {
            return 1;
        }
        if (isNumber(left) && isNumber(right)) {
            return Double.compare(asDouble(left), asDouble(right));
        }
        if (left instanceof String a && right instanceof String b) {
            return a.compareTo(b);
        }
        if (left instanceof Boolean a && right instanceof Boolean b) {
            return Boolean.compare(a, b);
        }
        throw new PrlExecutionException("不可比较：" + describe(left) + " 与 " + describe(right));
    }

    /** 集合里的稳定去重键：数值归一化，避免 {@code 1} 与 {@code 1L} 落成两个元素。 */
    public static Object hashKey(Object value) {
        if (isNumber(value)) {
            return asDouble(value);
        }
        return value;
    }

    // ------------------------------------------------------------------ 渲染

    /** 供日志、f-string、证据文本使用的可读形式。 */
    public static String display(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object value) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String text) {
            sb.append(text);
        } else if (value instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                append(sb, entry.getKey());
                sb.append(": ");
                append(sb, entry.getValue());
            }
            sb.append('}');
        } else if (value instanceof Set<?> set) {
            sb.append('{');
            boolean first = true;
            for (Object element : set) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                append(sb, element);
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                append(sb, list.get(i));
            }
            sb.append(']');
        } else if (value instanceof PrlCallable) {
            sb.append("<函数>");
        } else {
            sb.append(value);
        }
    }

    /** 运行时错误信息里的值描述：带类型，便于对不上号时定位。 */
    public static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        return value instanceof String text
                ? "string(\"" + text + "\")"
                : typeName(value) + "(" + display(value) + ")";
    }

    // ------------------------------------------------------------------ 构造

    public static List<Object> list(Object... elements) {
        List<Object> list = new ArrayList<>(elements.length);
        Collections.addAll(list, elements);
        return list;
    }
}