package com.potatotv.prl.lexer;

import java.util.Objects;

/**
 * 一个词法单元。
 *
 * <p>{@code value} 的含义按类型区分：字符串 / f-string 是解码转义后的内容（f-string 保留
 * {@code {...}} 占位符原文，不含引号）；数字是去掉单位后缀的数值文本，如 {@code "0xFF"}、
 * {@code "3.14"}；其它类型是源码原文。</p>
 *
 * <p>数字后紧跟的单位后缀（{@code 300s}、{@code 10MB}）由词法器直接拆到 {@link #unit()} 里，
 * 而不是让语法分析器去猜字符串边界——数值文本里同时可能出现 {@code x}/{@code b}/{@code e}，
 * 靠字符串再切一次迟早出错。</p>
 */
public record Token(TokenType type, String value, String unit, int line, int col) {

    public Token {
        Objects.requireNonNull(type, "type");
        value = value == null ? "" : value;
        unit = unit == null || unit.isEmpty() ? null : unit.toLowerCase(java.util.Locale.ROOT);
    }

    /** 设计文档 §2.5.1 里的四参构造形态（不带单位）。 */
    public Token(TokenType type, String value, int line, int col) {
        this(type, value, null, line, col);
    }

    public static Token eof(int line, int col) {
        return new Token(TokenType.EOF, "", null, line, col);
    }

    public boolean is(TokenType candidate) {
        return type == candidate;
    }

    /** 是否带单位后缀（时间量 ms/s/m/h 或数据量 kb/mb/gb）。 */
    public boolean hasUnit() {
        return unit != null;
    }

    @Override
    public String toString() {
        return type + "(" + value + (unit == null ? "" : unit) + ")@" + line + ":" + col;
    }
}