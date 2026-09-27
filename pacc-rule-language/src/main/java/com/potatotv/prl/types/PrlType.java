package com.potatotv.prl.types;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * PRL 类型（设计文档 §2.3）。
 *
 * <p>基础类型与集合类型见 §2.3.1、§2.3.2；宿主注册的上下文类型（§2.3.3）用 {@link Kind#HOST}
 * 表示，只带一个类型名，具体字段/方法查 {@link HostTypeRegistry}，这样类型本身不需要反向依赖宿主。</p>
 *
 * <p>另外两个 kind 不表示运行时的值，只用于标准库签名里的模式匹配：</p>
 * <ul>
 *   <li>{@link Kind#NUMBER} 匹配 {@code int} 与 {@code float}，对应 §2.4.5 里 {@code (number) -> number}
 *       这种写法，省掉 {@code abs} 这类函数为每种数值类型各写一遍重载；</li>
 *   <li>{@link Kind#ANY} 匹配一切，对应 §2.4.4 {@code record_evidence(key=string, value=any)} 与
 *       §2.4.2 里的 {@code list[Event]}（事件类型是宿主对象，规则语言不关心具体是哪种）。</li>
 * </ul>
 */
public final class PrlType {

    /** 类型种类。 */
    public enum Kind {
        INT,
        FLOAT,
        BOOL,
        STRING,
        /** {@code null} 字面量的类型，可赋给一切引用型（宿主对象、集合、字符串）。 */
        NULL,
        /** 无返回值，只出现在函数签名里。 */
        VOID,
        /** 推断不出具体类型（空列表元素、无标注的 {@code null} 等），与任何类型兼容。 */
        UNKNOWN,
        LIST,
        MAP,
        SET,
        TUPLE,
        HOST,
        FUNC,
        /** 泛型占位符，如 §2.4.3 {@code filter} 签名里的 {@code T}。 */
        VAR,
        /** 仅用于签名的数值模式，匹配 int 与 float。 */
        NUMBER,
        /** 仅用于签名的通配模式，匹配一切。 */
        ANY
    }

    public static final PrlType INT = new PrlType(Kind.INT, null, List.of(), List.of(), null);
    public static final PrlType FLOAT = new PrlType(Kind.FLOAT, null, List.of(), List.of(), null);
    public static final PrlType BOOL = new PrlType(Kind.BOOL, null, List.of(), List.of(), null);
    public static final PrlType STRING = new PrlType(Kind.STRING, null, List.of(), List.of(), null);
    public static final PrlType NULL = new PrlType(Kind.NULL, null, List.of(), List.of(), null);
    public static final PrlType VOID = new PrlType(Kind.VOID, null, List.of(), List.of(), null);
    public static final PrlType UNKNOWN = new PrlType(Kind.UNKNOWN, null, List.of(), List.of(), null);
    public static final PrlType NUMBER = new PrlType(Kind.NUMBER, null, List.of(), List.of(), null);
    public static final PrlType ANY = new PrlType(Kind.ANY, null, List.of(), List.of(), null);

    private final Kind kind;
    private final String name;
    private final List<PrlType> args;
    private final List<PrlType> params;
    private final PrlType returnType;

    private PrlType(Kind kind, String name, List<PrlType> args, List<PrlType> params, PrlType returnType) {
        this.kind = kind;
        this.name = name;
        this.args = args;
        this.params = params;
        this.returnType = returnType;
    }

    // ------------------------------------------------------------------ 构造

    /** {@code list[T]}。 */
    public static PrlType list(PrlType element) {
        return new PrlType(Kind.LIST, null, List.of(element), List.of(), null);
    }

    /** {@code map[K, V]}。 */
    public static PrlType map(PrlType key, PrlType value) {
        return new PrlType(Kind.MAP, null, List.of(key, value), List.of(), null);
    }

    /** {@code set[T]}。 */
    public static PrlType set(PrlType element) {
        return new PrlType(Kind.SET, null, List.of(element), List.of(), null);
    }

    /** {@code tuple[A, B, ...]}。 */
    public static PrlType tuple(List<PrlType> elements) {
        return new PrlType(Kind.TUPLE, null, List.copyOf(elements), List.of(), null);
    }

    /** 宿主注册的上下文类型，如 {@code PlayerContext}。 */
    public static PrlType host(String name) {
        return new PrlType(Kind.HOST, name, List.of(), List.of(), null);
    }

    /** 函数类型；返回类型放在最后一个参数位，与文档 §2.4.3 的 {@code (T) -> bool} 写法一致。 */
    public static PrlType func(List<PrlType> params, PrlType returnType) {
        return new PrlType(Kind.FUNC, null, List.of(), List.copyOf(params), returnType);
    }

    /** 泛型占位符，如 {@code T}、{@code K}、{@code U}。 */
    public static PrlType var(String name) {
        return new PrlType(Kind.VAR, name, List.of(), List.of(), null);
    }

    // ------------------------------------------------------------------ 访问

    public Kind kind() {
        return kind;
    }

    /** 仅 {@link Kind#HOST}（类型名）与 {@link Kind#VAR}（占位符名）非空。 */
    public String name() {
        return name;
    }

    /** LIST/SET 为 {@code [T]}，MAP 为 {@code [K, V]}，TUPLE 为各元素类型。 */
    public List<PrlType> args() {
        return args;
    }

    public List<PrlType> params() {
        return params;
    }

    public PrlType returnType() {
        return returnType;
    }

    public int argCount() {
        return args.size();
    }

    public PrlType arg(int index) {
        return args.get(index);
    }

    /** LIST/SET 的元素类型；其他 kind 调用会抛 {@link IllegalStateException}。 */
    public PrlType elementType() {
        return args.get(0);
    }

    public PrlType keyType() {
        return args.get(0);
    }

    public PrlType valueType() {
        return args.get(1);
    }

    public boolean isNumeric() {
        return kind == Kind.INT || kind == Kind.FLOAT;
    }

    public boolean isCollection() {
        return kind == Kind.LIST || kind == Kind.MAP || kind == Kind.SET || kind == Kind.TUPLE;
    }

    /** 引用型（可为 null）：宿主对象、集合、字符串。 */
    public boolean isReference() {
        return kind == Kind.HOST || kind == Kind.STRING || isCollection();
    }

    public boolean isUnknown() {
        return kind == Kind.UNKNOWN;
    }

    // ------------------------------------------------------------------ 显示

    /** 源码风格的写法，用于编译错误信息。 */
    public String display() {
        return switch (kind) {
            case INT -> "int";
            case FLOAT -> "float";
            case BOOL -> "bool";
            case STRING -> "string";
            case NULL -> "null";
            case VOID -> "void";
            case UNKNOWN -> "未知类型";
            case NUMBER -> "number";
            case ANY -> "any";
            case HOST, VAR -> name;
            case LIST -> "list[" + args.get(0).display() + "]";
            case SET -> "set[" + args.get(0).display() + "]";
            case MAP -> "map[" + args.get(0).display() + ", " + args.get(1).display() + "]";
            case TUPLE -> {
                StringJoiner joiner = new StringJoiner(", ", "tuple[", "]");
                for (PrlType t : args) {
                    joiner.add(t.display());
                }
                yield joiner.toString();
            }
            case FUNC -> {
                StringJoiner joiner = new StringJoiner(", ", "(", ")");
                for (PrlType p : params) {
                    joiner.add(p.display());
                }
                yield joiner + " -> " + (returnType == null ? "void" : returnType.display());
            }
        };
    }

    @Override
    public String toString() {
        return display();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PrlType other)) {
            return false;
        }
        return kind == other.kind
                && Objects.equals(name, other.name)
                && args.equals(other.args)
                && params.equals(other.params)
                && Objects.equals(returnType, other.returnType);
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, name, args, params, returnType);
    }

    // ------------------------------------------------------------------ 匹配与代换

    /**
     * 把 {@code actual} 与签名里的 {@code pattern} 对齐，同时累积泛型占位符的绑定。
     *
     * <p>用可变 {@code bindings} 而不是返回新 map：{@code filter(list[T], (T)->bool)} 要求同一个
     * {@code T} 在两处一致，因此实参必须按从左到右的顺序逐个匹配。</p>
     *
     * <p>匹配失败时 {@code bindings} 里可能留下部分绑定，调用方对每个候选签名各用一个新 map。</p>
     */
    public static boolean matches(PrlType pattern, PrlType actual, Map<String, PrlType> bindings) {
        if (pattern == null || actual == null) {
            return false;
        }
        if (pattern.kind == Kind.ANY || actual.kind == Kind.UNKNOWN) {
            return true;
        }
        if (pattern.kind == Kind.NUMBER) {
            return actual.isNumeric();
        }
        if (pattern.kind == Kind.VAR) {
            PrlType bound = bindings.get(pattern.name);
            if (bound == null) {
                bindings.put(pattern.name, actual);
                return true;
            }
            return bound.equals(actual) || bound.kind == Kind.UNKNOWN;
        }
        if (pattern.kind != actual.kind) {
            return false;
        }
        return switch (pattern.kind) {
            case LIST, SET -> matches(pattern.args.get(0), actual.args.get(0), bindings);
            case MAP -> matches(pattern.args.get(0), actual.args.get(0), bindings)
                    && matches(pattern.args.get(1), actual.args.get(1), bindings);
            case TUPLE -> {
                if (pattern.args.size() != actual.args.size()) {
                    yield false;
                }
                for (int i = 0; i < pattern.args.size(); i++) {
                    if (!matches(pattern.args.get(i), actual.args.get(i), bindings)) {
                        yield false;
                    }
                }
                yield true;
            }
            case HOST -> pattern.name.equals(actual.name);
            case FUNC -> {
                if (pattern.params.size() != actual.params.size()) {
                    yield false;
                }
                for (int i = 0; i < pattern.params.size(); i++) {
                    if (!matches(pattern.params.get(i), actual.params.get(i), bindings)) {
                        yield false;
                    }
                }
                yield matches(pattern.returnType, actual.returnType, bindings);
            }
            default -> true;
        };
    }

    /** 按绑定结果把签名里的占位符换成实际类型；未绑定的占位符保留原样。 */
    public static PrlType substitute(PrlType type, Map<String, PrlType> bindings) {
        if (type == null) {
            return null;
        }
        if (type.kind == Kind.VAR) {
            return bindings.getOrDefault(type.name, type);
        }
        switch (type.kind) {
            case LIST, SET -> {
                return type.kind == Kind.LIST
                        ? list(substitute(type.args.get(0), bindings))
                        : set(substitute(type.args.get(0), bindings));
            }
            case MAP -> {
                return map(substitute(type.args.get(0), bindings), substitute(type.args.get(1), bindings));
            }
            case TUPLE -> {
                List<PrlType> elements = new ArrayList<>(type.args.size());
                for (PrlType t : type.args) {
                    elements.add(substitute(t, bindings));
                }
                return tuple(elements);
            }
            case FUNC -> {
                List<PrlType> substituted = new ArrayList<>(type.params.size());
                for (PrlType p : type.params) {
                    substituted.add(substitute(p, bindings));
                }
                return func(substituted, substitute(type.returnType, bindings));
            }
            default -> {
                return type;
            }
        }
    }

    /** 便捷入口：单次匹配，不关心绑定结果。 */
    public static boolean matches(PrlType pattern, PrlType actual) {
        return matches(pattern, actual, new LinkedHashMap<>());
    }
}