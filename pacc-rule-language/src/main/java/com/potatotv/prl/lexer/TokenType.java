package com.potatotv.prl.lexer;

/**
 * PRL 词法单元类型。
 *
 * <p>对应设计文档 §2.5.1 的 {@code TokenType} 枚举。文档给出的枚举漏掉了 §2.2.2 明确列为关键字的
 * 类型名（{@code int/float/bool/string/list/map/set/tuple}）以及 §2.2.5、§2.3.5 示例里用到的
 * 复合赋值与空合并运算符，这里一并补齐，并用 {@link #isTypeKeyword()} 标注出来。</p>
 */
public enum TokenType {

    // ---- 字面量 ----
    INT_LITERAL,
    FLOAT_LITERAL,
    STRING_LITERAL,
    /** f-string，如 {@code f"player: {player.name}"}；value 为不含引号的原始文本。 */
    FSTRING_LITERAL,
    BOOL_LITERAL,
    NULL_LITERAL,

    // ---- 标识符 ----
    IDENTIFIER,

    // ---- 关键字：结构 ----
    RULE, INPUT, LET, WHEN, THEN, ELSE, END, IF, FOR, WHILE, RETURN, IN,

    // ---- 关键字：逻辑 ----
    AND, OR, NOT, TRUE, FALSE, NULL,

    // ---- 关键字：类型名 ----
    TYPE_INT, TYPE_FLOAT, TYPE_BOOL, TYPE_STRING, TYPE_LIST, TYPE_MAP, TYPE_SET, TYPE_TUPLE,

    // ---- 关键字：严重级 ----
    LOW, MEDIUM, HIGH, CRITICAL,

    // ---- 关键字：元数据 ----
    VERSION, AUTHOR, SEVERITY, CATEGORY, COOLDOWN, ENABLED, DESCRIPTION,

    // ---- 关键字：动作 ----
    EMIT_ALERT, RECORD_EVIDENCE, TRIGGER_REDSCREEN, LOG, BAN_FEATURE,

    // ---- 单位 ----
    PER_SECOND, PER_MINUTE, MS, S, M, H, KB, MB, GB,

    // ---- 运算符 ----
    PLUS, MINUS, STAR, SLASH, PERCENT, CARET,
    EQ_EQ, NOT_EQ, GT, LT, GTE, LTE,
    NOT_IN, DOT_DOT, PIPE, ARROW, QUESTION_QUESTION,
    PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN,

    // ---- 分隔符 ----
    LPAREN, RPAREN, LBRACE, RBRACE, LBRACKET, RBRACKET,
    COMMA, COLON, SEMICOLON, DOT, ASSIGN,

    // ---- 特殊 ----
    COMMENT, NEWLINE, EOF, ERROR;

    /** 类型名关键字（{@code int}、{@code list} …）。 */
    public boolean isTypeKeyword() {
        return switch (this) {
            case TYPE_INT, TYPE_FLOAT, TYPE_BOOL, TYPE_STRING,
                 TYPE_LIST, TYPE_MAP, TYPE_SET, TYPE_TUPLE -> true;
            default -> false;
        };
    }

    /** 可以单独出现的单位关键字（数值后紧跟的单位后缀不产生独立 token）。 */
    public boolean isUnit() {
        return switch (this) {
            case PER_SECOND, PER_MINUTE, MS, S, M, H, KB, MB, GB -> true;
            default -> false;
        };
    }

    /** 复合赋值运算符。 */
    public boolean isCompoundAssign() {
        return switch (this) {
            case PLUS_ASSIGN, MINUS_ASSIGN, STAR_ASSIGN, SLASH_ASSIGN -> true;
            default -> false;
        };
    }
}