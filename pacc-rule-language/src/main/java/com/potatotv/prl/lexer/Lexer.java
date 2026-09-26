package com.potatotv.prl.lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PRL 词法分析器（设计文档 §2.5）。
 *
 * <p>手写扫描器，不用正则回溯：数值后紧跟的单位、{@code not in} 这类两词运算符、f-string 插值
 * 都需要上下文判断，正则反而更难维护。</p>
 *
 * <p>注释与换行会产出 {@link TokenType#COMMENT}/{@link TokenType#NEWLINE} token 而不是被丢弃，
 * 因为管理端编辑器要拿它们做语法高亮；语法分析器侧统一跳过。</p>
 */
public final class Lexer {

    /** 关键字表。逻辑运算符大小写不敏感（便于 LuaJ 规则迁移），其余按文档严格小写。 */
    private static final Map<String, TokenType> KEYWORDS = Map.ofEntries(
            Map.entry("rule", TokenType.RULE),
            Map.entry("input", TokenType.INPUT),
            Map.entry("let", TokenType.LET),
            Map.entry("when", TokenType.WHEN),
            Map.entry("then", TokenType.THEN),
            Map.entry("else", TokenType.ELSE),
            Map.entry("end", TokenType.END),
            Map.entry("if", TokenType.IF),
            Map.entry("for", TokenType.FOR),
            Map.entry("while", TokenType.WHILE),
            Map.entry("return", TokenType.RETURN),
            Map.entry("in", TokenType.IN),
            Map.entry("and", TokenType.AND),
            Map.entry("or", TokenType.OR),
            Map.entry("not", TokenType.NOT),
            Map.entry("true", TokenType.TRUE),
            Map.entry("false", TokenType.FALSE),
            Map.entry("null", TokenType.NULL),
            Map.entry("int", TokenType.TYPE_INT),
            Map.entry("float", TokenType.TYPE_FLOAT),
            Map.entry("bool", TokenType.TYPE_BOOL),
            Map.entry("string", TokenType.TYPE_STRING),
            Map.entry("list", TokenType.TYPE_LIST),
            Map.entry("map", TokenType.TYPE_MAP),
            Map.entry("set", TokenType.TYPE_SET),
            Map.entry("tuple", TokenType.TYPE_TUPLE),
            Map.entry("low", TokenType.LOW),
            Map.entry("medium", TokenType.MEDIUM),
            Map.entry("high", TokenType.HIGH),
            Map.entry("critical", TokenType.CRITICAL),
            Map.entry("version", TokenType.VERSION),
            Map.entry("author", TokenType.AUTHOR),
            Map.entry("severity", TokenType.SEVERITY),
            Map.entry("category", TokenType.CATEGORY),
            Map.entry("cooldown", TokenType.COOLDOWN),
            Map.entry("enabled", TokenType.ENABLED),
            Map.entry("description", TokenType.DESCRIPTION),
            Map.entry("emit_alert", TokenType.EMIT_ALERT),
            Map.entry("record_evidence", TokenType.RECORD_EVIDENCE),
            Map.entry("trigger_redscreen", TokenType.TRIGGER_REDSCREEN),
            Map.entry("log", TokenType.LOG),
            Map.entry("ban_feature", TokenType.BAN_FEATURE),
            Map.entry("per_second", TokenType.PER_SECOND),
            Map.entry("per_minute", TokenType.PER_MINUTE),
            Map.entry("ms", TokenType.MS),
            Map.entry("s", TokenType.S),
            Map.entry("m", TokenType.M),
            Map.entry("h", TokenType.H),
            Map.entry("kb", TokenType.KB),
            Map.entry("mb", TokenType.MB),
            Map.entry("gb", TokenType.GB));

    /** 数值后缀单位，按长度倒序匹配，避免 {@code 10mb} 被当成 {@code 10m} + {@code b}。 */
    private static final String[] UNIT_SUFFIXES = {"ms", "kb", "mb", "gb", "s", "m", "h"};

    private final String source;
    private int pos;
    private int line = 1;
    private int col = 1;

    public Lexer(String source) {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为 null");
        }
        // 统一换行符，避免 Windows 的 \r\n 在列号与字符串内容里留下 \r
        this.source = source.replace("\r\n", "\n").replace('\r', '\n');
    }

    public List<Token> tokenize() {
        List<Token> tokens = new ArrayList<>();
        while (pos < source.length()) {
            char c = peek(0);
            if (c == '\n') {
                tokens.add(new Token(TokenType.NEWLINE, "\n", line, col));
                advance();
            } else if (c == ' ' || c == '\t' || c == '\f') {
                advance();
            } else if (c == '#') {
                tokens.add(readComment());
            } else if (Character.isDigit(c)) {
                tokens.add(readNumber());
            } else if (c == '"' || c == '\'') {
                tokens.add(readString());
            } else if ((c == 'f' || c == 'F') && (peek(1) == '"' || peek(1) == '\'')) {
                tokens.add(readFString());
            } else if (isIdentifierStart(c)) {
                tokens.add(readIdentifierOrKeyword());
            } else {
                tokens.add(readOperator());
            }
        }
        tokens.add(Token.eof(line, col));
        return tokens;
    }

    // ------------------------------------------------------------------ 扫描

    private Token readComment() {
        int startLine = line;
        int startCol = col;
        int start = pos;
        while (pos < source.length() && peek(0) != '\n') {
            advance();
        }
        return new Token(TokenType.COMMENT, source.substring(start, pos).trim(), startLine, startCol);
    }

    private Token readNumber() {
        int startLine = line;
        int startCol = col;
        int start = pos;
        boolean isFloat = false;

        if (peek(0) == '0' && (peek(1) == 'x' || peek(1) == 'X')) {
            advance();
            advance();
            int digits = 0;
            while (isHexDigit(peek(0)) || peek(0) == '_') {
                if (peek(0) != '_') {
                    digits++;
                }
                advance();
            }
            if (digits == 0) {
                throw new LexException("0x 后缺少十六进制数字", startLine, startCol);
            }
        } else if (peek(0) == '0' && (peek(1) == 'b' || peek(1) == 'B')) {
            advance();
            advance();
            int digits = 0;
            while (peek(0) == '0' || peek(0) == '1' || peek(0) == '_') {
                if (peek(0) != '_') {
                    digits++;
                }
                advance();
            }
            if (digits == 0) {
                throw new LexException("0b 后缺少二进制数字", startLine, startCol);
            }
        } else {
            consumeDigits();
            // 小数点后必须跟数字，否则 "1.foo" 之类的成员访问会被误判成浮点数
            if (peek(0) == '.' && Character.isDigit(peek(1))) {
                isFloat = true;
                advance();
                consumeDigits();
            }
            if (peek(0) == 'e' || peek(0) == 'E') {
                int save = pos;
                int saveLine = line;
                int saveCol = col;
                advance();
                if (peek(0) == '+' || peek(0) == '-') {
                    advance();
                }
                if (Character.isDigit(peek(0))) {
                    isFloat = true;
                    consumeDigits();
                } else {
                    // 回退：e 后面不是指数，交给后续标识符扫描
                    pos = save;
                    line = saveLine;
                    col = saveCol;
                }
            }
        }

        String numeric = source.substring(start, pos);
        String unit = readUnitSuffix();
        TokenType type = isFloat ? TokenType.FLOAT_LITERAL : TokenType.INT_LITERAL;
        return new Token(type, numeric, unit, startLine, startCol);
    }

    private void consumeDigits() {
        while (Character.isDigit(peek(0)) || peek(0) == '_') {
            advance();
        }
    }

    /** 读取数值后紧跟的单位后缀，返回归一化后的小写单位；没有单位返回 null。 */
    private String readUnitSuffix() {
        for (String candidate : UNIT_SUFFIXES) {
            if (matchesIgnoreCase(candidate)) {
                // 单位后面不能再跟标识符字符，否则 "10msec" 会被切成 10m + sec
                char after = peek(candidate.length());
                if (isIdentifierPart(after)) {
                    continue;
                }
                for (int i = 0; i < candidate.length(); i++) {
                    advance();
                }
                return candidate;
            }
        }
        return null;
    }

    private Token readString() {
        int startLine = line;
        int startCol = col;
        char quote = peek(0);
        advance();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= source.length() || peek(0) == '\n') {
                throw new LexException("字符串缺少结束引号 " + quote, startLine, startCol);
            }
            char c = advance();
            if (c == quote) {
                break;
            }
            if (c == '\\') {
                readEscape(sb, startLine, startCol);
            } else {
                sb.append(c);
            }
        }
        return new Token(TokenType.STRING_LITERAL, sb.toString(), startLine, startCol);
    }

    private Token readFString() {
        int startLine = line;
        int startCol = col;
        advance(); // f
        char quote = peek(0);
        advance();
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= source.length() || peek(0) == '\n') {
                throw new LexException("f-string 缺少结束引号 " + quote, startLine, startCol);
            }
            char c = advance();
            if (c == quote) {
                break;
            }
            if (c == '\\') {
                readEscape(sb, startLine, startCol);
            } else {
                sb.append(c);
            }
        }
        return new Token(TokenType.FSTRING_LITERAL, sb.toString(), startLine, startCol);
    }

    private void readEscape(StringBuilder sb, int startLine, int startCol) {
        if (pos >= source.length()) {
            throw new LexException("转义字符不完整", startLine, startCol);
        }
        char e = advance();
        switch (e) {
            case 'n' -> sb.append('\n');
            case 't' -> sb.append('\t');
            case 'r' -> sb.append('\r');
            case '\\' -> sb.append('\\');
            case '"' -> sb.append('"');
            case '\'' -> sb.append('\'');
            case 'u' -> {
                if (pos + 4 > source.length()) {
                    throw new LexException("\\u 转义缺少 4 位十六进制", startLine, startCol);
                }
                int code = 0;
                for (int i = 0; i < 4; i++) {
                    char h = peek(0);
                    if (!isHexDigit(h)) {
                        throw new LexException("\\u 转义含非法十六进制字符 '" + h + "'", line, col);
                    }
                    code = code * 16 + Character.digit(h, 16);
                    advance();
                }
                sb.append((char) code);
            }
            default -> throw new LexException("不支持的转义字符 \\" + e, line, col);
        }
    }

    private Token readIdentifierOrKeyword() {
        int startLine = line;
        int startCol = col;
        int start = pos;
        while (isIdentifierPart(peek(0))) {
            advance();
        }
        String text = source.substring(start, pos);

        // "not in" 合成单个 NOT_IN token：表达式里 NOT 与 IN 相邻但语义是一个运算符
        if ("not".equalsIgnoreCase(text) && matchesNotInAhead()) {
            return new Token(TokenType.NOT_IN, "not in", null, startLine, startCol);
        }

        TokenType keyword = KEYWORDS.get(text.toLowerCase(Locale.ROOT));
        if (keyword == null) {
            return new Token(TokenType.IDENTIFIER, text, startLine, startCol);
        }
        return new Token(keyword, text, startLine, startCol);
    }

    /** 当前位置之后（跳过空白）是否正好是独立的 {@code in}。 */
    private boolean matchesNotInAhead() {
        int p = pos;
        boolean sawSpace = false;
        while (p < source.length() && (source.charAt(p) == ' ' || source.charAt(p) == '\t')) {
            p++;
            sawSpace = true;
        }
        if (!sawSpace || p + 2 > source.length()) {
            return false;
        }
        if (!source.regionMatches(true, p, "in", 0, 2)) {
            return false;
        }
        char after = p + 2 < source.length() ? source.charAt(p + 2) : '\0';
        if (isIdentifierPart(after)) {
            return false;
        }
        // 消费 "not" 与空白以及 "in"
        while (pos < p + 2) {
            advance();
        }
        return true;
    }

    private Token readOperator() {
        int startLine = line;
        int startCol = col;
        char c = peek(0);
        char n = peek(1);

        if (c == '-' && n == '>') {
            return two(TokenType.ARROW, startLine, startCol);
        }
        if (c == '|' && n == '>') {
            return two(TokenType.PIPE, startLine, startCol);
        }
        if (c == '.' && n == '.') {
            return two(TokenType.DOT_DOT, startLine, startCol);
        }
        if (c == '=' && n == '=') {
            return two(TokenType.EQ_EQ, startLine, startCol);
        }
        if (c == '!' && n == '=') {
            return two(TokenType.NOT_EQ, startLine, startCol);
        }
        if (c == '>' && n == '=') {
            return two(TokenType.GTE, startLine, startCol);
        }
        if (c == '<' && n == '=') {
            return two(TokenType.LTE, startLine, startCol);
        }
        if (c == '+' && n == '=') {
            return two(TokenType.PLUS_ASSIGN, startLine, startCol);
        }
        if (c == '-' && n == '=') {
            return two(TokenType.MINUS_ASSIGN, startLine, startCol);
        }
        if (c == '*' && n == '=') {
            return two(TokenType.STAR_ASSIGN, startLine, startCol);
        }
        if (c == '/' && n == '=') {
            return two(TokenType.SLASH_ASSIGN, startLine, startCol);
        }
        if (c == '?' && n == '?') {
            return two(TokenType.QUESTION_QUESTION, startLine, startCol);
        }

        TokenType single = switch (c) {
            case '+' -> TokenType.PLUS;
            case '-' -> TokenType.MINUS;
            case '*' -> TokenType.STAR;
            case '/' -> TokenType.SLASH;
            case '%' -> TokenType.PERCENT;
            case '^' -> TokenType.CARET;
            case '>' -> TokenType.GT;
            case '<' -> TokenType.LT;
            case '=' -> TokenType.ASSIGN;
            case '(' -> TokenType.LPAREN;
            case ')' -> TokenType.RPAREN;
            case '{' -> TokenType.LBRACE;
            case '}' -> TokenType.RBRACE;
            case '[' -> TokenType.LBRACKET;
            case ']' -> TokenType.RBRACKET;
            case ',' -> TokenType.COMMA;
            case ':' -> TokenType.COLON;
            case ';' -> TokenType.SEMICOLON;
            case '.' -> TokenType.DOT;
            default -> null;
        };
        if (single == null) {
            throw new LexException("非法字符 '" + c + "'", startLine, startCol);
        }
        advance();
        return new Token(single, String.valueOf(c), startLine, startCol);
    }

    private Token two(TokenType type, int startLine, int startCol) {
        String text = source.substring(pos, pos + 2);
        advance();
        advance();
        return new Token(type, text, startLine, startCol);
    }

    // ------------------------------------------------------------------ 字符工具

    private char peek(int offset) {
        int at = pos + offset;
        return at >= 0 && at < source.length() ? source.charAt(at) : '\0';
    }

    private char advance() {
        char c = source.charAt(pos++);
        if (c == '\n') {
            line++;
            col = 1;
        } else {
            col++;
        }
        return c;
    }

    private boolean matchesIgnoreCase(String candidate) {
        return pos + candidate.length() <= source.length()
                && source.regionMatches(true, pos, candidate, 0, candidate.length());
    }

    private static boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isHexDigit(char c) {
        return Character.digit(c, 16) >= 0;
    }
}