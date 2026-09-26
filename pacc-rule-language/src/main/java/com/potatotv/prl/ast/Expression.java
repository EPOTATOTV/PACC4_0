package com.potatotv.prl.ast;

import java.util.List;

/**
 * 表达式（设计文档 §2.6.2）。
 *
 * <p>几个和文档示例对应的落点：</p>
 * <ul>
 *   <li>{@code click_pattern.is_human_like()} → {@link MethodCall}（宿主方法调用），
 *       {@code click_pattern.to_json()} 同理；</li>
 *   <li>{@code attack_events |> filter(e -> ...)} → {@link PipeExpr}，管道左值作为右侧调用的首个实参；</li>
 *   <li>{@code 8 per_second} / {@code 300s} / {@code 10MB} → {@link UnitLiteral}；</li>
 *   <li>{@code emit_alert(type = "KILL_AURA", ...)} → {@link CallExpr} 的具名实参。</li>
 * </ul>
 */
public sealed interface Expression extends AstNode
        permits Expression.IntLiteral, Expression.FloatLiteral, Expression.StringLiteral,
                Expression.InterpolatedString, Expression.BoolLiteral, Expression.NullLiteral,
                Expression.SeverityLiteral, Expression.UnitLiteral, Expression.Identifier,
                Expression.BinaryExpr, Expression.UnaryExpr, Expression.CallExpr, Expression.MethodCall,
                Expression.MemberAccess, Expression.IndexAccess, Expression.LambdaExpr,
                Expression.ListLiteral, Expression.MapLiteral, Expression.SetLiteral,
                Expression.TupleLiteral, Expression.RangeExpr, Expression.PipeExpr, Expression.IfExpr {

    /** 整数字面量，运行时为 64 位有符号整数。 */
    record IntLiteral(long value, int line, int col) implements Expression {
    }

    /** 浮点字面量，运行时为 IEEE 754 双精度。 */
    record FloatLiteral(double value, int line, int col) implements Expression {
    }

    /** 普通字符串字面量。 */
    record StringLiteral(String value, int line, int col) implements Expression {
    }

    /**
     * f-string：{@code f"player: {player.name}"}。
     *
     * @param parts 按顺序拼接的片段，字面量片段是 {@link StringLiteral}，其余为插值表达式
     */
    record InterpolatedString(List<Expression> parts, int line, int col) implements Expression {

        public InterpolatedString {
            parts = parts == null ? List.of() : List.copyOf(parts);
        }
    }

    record BoolLiteral(boolean value, int line, int col) implements Expression {
    }

    record NullLiteral(int line, int col) implements Expression {
    }

    /** 严重级字面量，如 {@code severity = critical} 里的 {@code critical}。 */
    record SeverityLiteral(Severity value, int line, int col) implements Expression {
    }

    /**
     * 带单位字面量。运行时归一化规则见 {@link UnitKind}，归一化入口在本记录上：
     * {@link #millis()}、{@link #bytes()}、{@link #perSecond()}。
     */
    record UnitLiteral(double magnitude, UnitKind kind, String unitText, int line, int col) implements Expression {

        /** 时间量归一化为毫秒。 */
        public long millis() {
            long factor = switch (unitText) {
                case "ms" -> 1L;
                case "s" -> 1_000L;
                case "m" -> 60_000L;
                case "h" -> 3_600_000L;
                default -> throw new IllegalStateException("非时间单位: " + unitText);
            };
            return Math.round(magnitude * factor);
        }

        /** 数据量归一化为字节；KB/MB/GB 按 1024 进制（与内核/驱动侧惯例一致）。 */
        public long bytes() {
            long factor = switch (unitText) {
                case "kb" -> 1024L;
                case "mb" -> 1024L * 1024L;
                case "gb" -> 1024L * 1024L * 1024L;
                default -> throw new IllegalStateException("非数据量单位: " + unitText);
            };
            return Math.round(magnitude * factor);
        }

        /** 速率归一化为「每秒次数」。 */
        public double perSecond() {
            return switch (unitText) {
                case "per_second" -> magnitude;
                case "per_minute" -> magnitude / 60.0d;
                default -> throw new IllegalStateException("非速率单位: " + unitText);
            };
        }
    }

    /** 标识符引用。 */
    record Identifier(String name, int line, int col) implements Expression {
    }

    /** 二元运算；{@code op} 取源码写法的规范形式，如 {@code "AND"}、{@code "+"}、{@code "??"}。 */
    record BinaryExpr(String op, Expression left, Expression right, int line, int col) implements Expression {
    }

    /** 一元运算：{@code NOT}、{@code -}、{@code +}。 */
    record UnaryExpr(String op, Expression operand, int line, int col) implements Expression {
    }

    /** 具名实参。{@code name} 为 null 表示位置实参。 */
    record Arg(String name, Expression value) {
    }

    /** 普通函数调用：{@code rate(attack_events, 1s)}。 */
    record CallExpr(String name, List<Arg> args, int line, int col) implements Expression {

        public CallExpr {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    /** 宿主对象方法调用：{@code click_pattern.is_human_like()}。 */
    record MethodCall(Expression receiver, String name, List<Arg> args, int line, int col) implements Expression {

        public MethodCall {
            args = args == null ? List.of() : List.copyOf(args);
        }
    }

    /** 宿主对象字段访问：{@code player.is_trusted}。 */
    record MemberAccess(Expression target, String member, int line, int col) implements Expression {
    }

    /** 下标访问：{@code events[0]}、{@code counts["key"]}。 */
    record IndexAccess(Expression target, Expression index, int line, int col) implements Expression {
    }

    /** Lambda：{@code e -> e.target_changed} 或 {@code (a, b) -> a + b}。 */
    record LambdaExpr(List<String> params, Expression body, int line, int col) implements Expression {

        public LambdaExpr {
            params = params == null ? List.of() : List.copyOf(params);
        }
    }

    record ListLiteral(List<Expression> elements, int line, int col) implements Expression {

        public ListLiteral {
            elements = elements == null ? List.of() : List.copyOf(elements);
        }
    }

    /** 映射表条目。 */
    record MapEntry(Expression key, Expression value) {
    }

    /** {@code {"key": value}} 形式的映射表（保持插入顺序）。 */
    record MapLiteral(List<MapEntry> entries, int line, int col) implements Expression {

        public MapLiteral {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    /** {@code {1, 2, 3}} 形式的集合（去重、保持插入顺序）。 */
    record SetLiteral(List<Expression> elements, int line, int col) implements Expression {

        public SetLiteral {
            elements = elements == null ? List.of() : List.copyOf(elements);
        }
    }

    /** {@code (1, "hello", true)} 形式的定长元组。 */
    record TupleLiteral(List<Expression> elements, int line, int col) implements Expression {

        public TupleLiteral {
            elements = elements == null ? List.of() : List.copyOf(elements);
        }
    }

    /** 区间：{@code 1..10}，左闭右闭，两端为 int。 */
    record RangeExpr(Expression start, Expression end, int line, int col) implements Expression {
    }

    /**
     * 管道：{@code left |> f(a)} 等价于 {@code f(left, a)}。
     * 右值必须是函数调用或 lambda，编译期校验。
     */
    record PipeExpr(Expression left, Expression right, int line, int col) implements Expression {
    }

    /** 表达式形式的 if / else（三元）。 */
    record IfExpr(Expression condition, Expression thenExpr, Expression elseExpr, int line, int col)
            implements Expression {
    }
}