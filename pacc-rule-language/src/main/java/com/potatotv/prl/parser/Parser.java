package com.potatotv.prl.parser;

import com.potatotv.prl.ast.Expression;
import com.potatotv.prl.ast.InputDecl;
import com.potatotv.prl.ast.LetDecl;
import com.potatotv.prl.ast.Rule;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.ast.RuleMetadata;
import com.potatotv.prl.ast.Severity;
import com.potatotv.prl.ast.Statement;
import com.potatotv.prl.ast.TypeRef;
import com.potatotv.prl.ast.UnitKind;
import com.potatotv.prl.ast.WhenClause;
import com.potatotv.prl.lexer.Lexer;
import com.potatotv.prl.lexer.Token;
import com.potatotv.prl.lexer.TokenType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PRL 递归下降语法分析器（设计文档 §2.6.1）。
 *
 * <p>不用第三方解析器框架。注释、换行、分号在构造时就被滤掉：规则块用 {@code end}/{@code }}
 * 收尾，元数据条目靠关键字自识别，语句自身能判断在哪结束，因此换行在这个语法里不承担语义，
 * 过滤掉可以省掉一大堆「这里能不能跨行」的特判。</p>
 *
 * <p>位置信息保留在 token 上，报错指到具体行列。</p>
 */
public final class Parser {

    /** 元数据条目起始关键字。 */
    private static final Set<TokenType> METADATA_KEYS = EnumSet.of(
            TokenType.VERSION, TokenType.AUTHOR, TokenType.SEVERITY, TokenType.CATEGORY,
            TokenType.COOLDOWN, TokenType.ENABLED, TokenType.DESCRIPTION);

    private static final Set<TokenType> COMPARISON_OPS = EnumSet.of(
            TokenType.EQ_EQ, TokenType.NOT_EQ, TokenType.GT, TokenType.LT,
            TokenType.GTE, TokenType.LTE, TokenType.IN, TokenType.NOT_IN);

    private final List<Token> tokens;
    private int pos;

    public Parser(List<Token> rawTokens) {
        List<Token> significant = new ArrayList<>(rawTokens.size());
        for (Token t : rawTokens) {
            if (t.type() == TokenType.COMMENT || t.type() == TokenType.NEWLINE || t.type() == TokenType.SEMICOLON) {
                continue;
            }
            significant.add(t);
        }
        this.tokens = Collections.unmodifiableList(significant);
    }

    /**
     * 便捷入口：直接对源码做词法 + 语法分析。
     */
    public static RuleFile parseSource(String source) {
        return new Parser(new Lexer(source).tokenize()).parse();
    }

    /**
     * 解析单个表达式（f-string 插值片段、管理端编辑器按需使用）。
     *
     * @param baseLine 片段首行在源文件中的行号，用于把报错位置还原到源文件
     * @param baseCol  片段首列在源文件中的列号
     */
    public static Expression parseExpressionFragment(String source, int baseLine, int baseCol) {
        List<Token> raw = new Lexer(source).tokenize();
        List<Token> shifted = new ArrayList<>(raw.size());
        for (Token t : raw) {
            int line = baseLine + t.line() - 1;
            int col = t.line() == 1 ? baseCol + t.col() - 1 : t.col();
            shifted.add(new Token(t.type(), t.value(), t.unit(), line, col));
        }
        Parser parser = new Parser(shifted);
        Expression expr = parser.parseExpression();
        if (!parser.check(TokenType.EOF)) {
            throw parser.error("表达式片段存在多余内容 '" + parser.peek().value() + "'");
        }
        return expr;
    }

    // ------------------------------------------------------------------ 规则文件

    public RuleFile parse() {
        Token first = peek();
        List<Rule> rules = new ArrayList<>();
        while (!check(TokenType.EOF)) {
            rules.add(parseRule());
        }
        return new RuleFile(rules, first.line(), first.col());
    }

    private Rule parseRule() {
        Token keyword = expect(TokenType.RULE, "rule");
        Token nameToken = expect(TokenType.STRING_LITERAL, "规则名字符串");
        expect(TokenType.LBRACE, "{");

        RuleMetadata metadata = RuleMetadata.empty(keyword.line(), keyword.col());
        InputDecl input = null;
        List<LetDecl> lets = new ArrayList<>();
        WhenClause when = null;
        List<Statement> then = null;

        while (!check(TokenType.RBRACE)) {
            Token t = peek();
            if (METADATA_KEYS.contains(t.type())) {
                metadata = parseMetadataEntry(metadata);
            } else if (check(TokenType.INPUT)) {
                if (input != null) {
                    throw error("重复的 input 声明块");
                }
                input = parseInputDecl();
            } else if (check(TokenType.LET)) {
                lets.add(parseLet());
            } else if (check(TokenType.WHEN)) {
                if (when != null) {
                    throw error("重复的 when 子句");
                }
                when = parseWhen();
            } else if (check(TokenType.THEN)) {
                if (then != null) {
                    throw error("重复的 then 块");
                }
                then = parseThenBlock();
            } else {
                throw error("规则体内出现意外 token " + t);
            }
        }
        Token close = expect(TokenType.RBRACE, "}");
        return new Rule(nameToken.value(), metadata, input, lets, when,
                then == null ? List.of() : then, close.line(), close.col());
    }

    private RuleMetadata parseMetadataEntry(RuleMetadata metadata) {
        Token key = advance();
        expect(TokenType.COLON, "元数据条目的 ':'");
        return switch (key.type()) {
            case VERSION -> metadata.withVersion(
                    expect(TokenType.STRING_LITERAL, "version 字符串").value(), key.line(), key.col());
            case AUTHOR -> metadata.withAuthor(
                    expect(TokenType.STRING_LITERAL, "author 字符串").value(), key.line(), key.col());
            case DESCRIPTION -> metadata.withDescription(
                    expect(TokenType.STRING_LITERAL, "description 字符串").value(), key.line(), key.col());
            case CATEGORY -> metadata.withCategory(
                    expect(TokenType.STRING_LITERAL, "category 字符串").value(), key.line(), key.col());
            case SEVERITY -> metadata.withSeverity(parseSeverityKeyword(), key.line(), key.col());
            case COOLDOWN -> metadata.withCooldownMillis(parseCooldownMillis(), key.line(), key.col());
            case ENABLED -> metadata.withEnabled(parseBoolLiteral(), key.line(), key.col());
            default -> throw error("未知的元数据字段 " + key.value());
        };
    }

    private Severity parseSeverityKeyword() {
        Token t = peek();
        return switch (t.type()) {
            case LOW -> severity(Severity.LOW);
            case MEDIUM -> severity(Severity.MEDIUM);
            case HIGH -> severity(Severity.HIGH);
            case CRITICAL -> severity(Severity.CRITICAL);
            default -> throw error("severity 需要 low/medium/high/critical，实际为 " + t);
        };
    }

    private Severity severity(Severity value) {
        advance();
        return value;
    }

    private long parseCooldownMillis() {
        Expression.UnitLiteral literal = parseUnitLiteral("cooldown");
        if (literal.kind() != UnitKind.TIME) {
            throw new ParseException("cooldown 的单位必须是时间单位（ms/s/m/h），实际为 " + literal.unitText(),
                    literal.line(), literal.col());
        }
        return literal.millis();
    }

    /**
     * 解析「数值 + 单位」。单位可以紧贴数字（{@code 300s}），也可以被空白分开（{@code 300 s}），
     * 两种写法都接受：贴写是文档 §2.2.3 的推荐写法，分开写能在紧凑语法里少一个坑。
     */
    private Expression.UnitLiteral parseUnitLiteral(String what) {
        Token t = peek();
        if (t.type() != TokenType.INT_LITERAL && t.type() != TokenType.FLOAT_LITERAL) {
            throw error(what + " 需要带单位的数值字面量（如 300s），实际为 " + t);
        }
        advance();
        String unit = t.unit();
        if (unit == null && (peek().type().isUnit())) {
            unit = unitTextOf(advance().type());
        }
        if (unit == null) {
            throw error(what + " 缺少单位（ms/s/m/h、KB/MB/GB、per_second/per_minute）", t.line(), t.col());
        }
        return new Expression.UnitLiteral(rawNumericValue(t), unitKindOf(unit), unit, t.line(), t.col());
    }

    private boolean parseBoolLiteral() {
        Token t = peek();
        if (t.type() == TokenType.TRUE) {
            advance();
            return true;
        }
        if (t.type() == TokenType.FALSE) {
            advance();
            return false;
        }
        throw error("enabled 需要 true/false，实际为 " + t);
    }

    private InputDecl parseInputDecl() {
        Token keyword = expect(TokenType.INPUT, "input");
        expect(TokenType.LBRACE, "{");
        List<InputDecl.Field> fields = new ArrayList<>();
        while (!check(TokenType.RBRACE)) {
            Token nameToken = expect(TokenType.IDENTIFIER, "输入变量名");
            expect(TokenType.COLON, "输入变量的 ':'");
            TypeRef type = parseTypeRef();
            fields.add(new InputDecl.Field(nameToken.value(), type, nameToken.line(), nameToken.col()));
        }
        expect(TokenType.RBRACE, "}");
        return new InputDecl(fields, keyword.line(), keyword.col());
    }

    private TypeRef parseTypeRef() {
        Token nameToken = peek();
        if (!nameToken.type().isTypeKeyword() && nameToken.type() != TokenType.IDENTIFIER) {
            throw error("需要类型名，实际为 " + nameToken);
        }
        advance();
        String name = nameToken.type().isTypeKeyword()
                ? nameToken.value().toLowerCase(Locale.ROOT)
                : nameToken.value();

        List<TypeRef> args = new ArrayList<>();
        if (check(TokenType.LBRACKET)) {
            advance();
            args.add(parseTypeRef());
            while (check(TokenType.COMMA)) {
                advance();
                args.add(parseTypeRef());
            }
            expect(TokenType.RBRACKET, "]");
        }
        return new TypeRef(name, args, nameToken.line(), nameToken.col());
    }

    private LetDecl parseLet() {
        Token keyword = expect(TokenType.LET, "let");
        Token nameToken = expectVariableName();
        TypeRef declared = null;
        if (check(TokenType.COLON)) {
            advance();
            declared = parseTypeRef();
        }
        expect(TokenType.ASSIGN, "'='");
        Expression init = parseExpression();
        return new LetDecl(nameToken.value(), declared, init, keyword.line(), keyword.col());
    }

    private WhenClause parseWhen() {
        Token keyword = expect(TokenType.WHEN, "when");
        expect(TokenType.COLON, "when 后的 ':'");
        Expression condition = parseExpression();
        return new WhenClause(condition, keyword.line(), keyword.col());
    }

    private List<Statement> parseThenBlock() {
        expect(TokenType.THEN, "then");
        expect(TokenType.COLON, "then 后的 ':'");
        return parseStatementList(TokenType.RBRACE);
    }

    // ------------------------------------------------------------------ 语句

    /**
     * 解析语句列表，遇到任一终止 token 停止。
     *
     * <p>{@code if} 的 then 分支要同时把 {@code ELSE} 当终止符，否则 {@code else} 会被当成
     * 下一个语句的起始去解析表达式。</p>
     */
    private List<Statement> parseStatementList(TokenType... terminators) {
        List<Statement> statements = new ArrayList<>();
        while (!isTerminator(terminators) && !check(TokenType.EOF)) {
            statements.add(parseStatement());
        }
        return statements;
    }

    private boolean isTerminator(TokenType[] terminators) {
        TokenType type = peek().type();
        for (TokenType terminator : terminators) {
            if (type == terminator) {
                return true;
            }
        }
        return false;
    }

    private Statement parseStatement() {
        Token t = peek();
        return switch (t.type()) {
            case LET -> parseLet();
            case IF -> parseIfStatement();
            case FOR -> parseForStatement();
            case WHILE -> parseWhileStatement();
            case RETURN -> parseReturnStatement();
            default -> parseSimpleStatement();
        };
    }

    private Statement parseIfStatement() {
        return parseIfStatement(true);
    }

    /**
     * @param expectEnd else-if 链里内层的 if 与外层共用同一个 {@code end}，内层不能自己吃掉
     */
    private Statement parseIfStatement(boolean expectEnd) {
        Token keyword = expect(TokenType.IF, "if");
        Expression condition = parseExpression();
        expect(TokenType.COLON, "if 条件后的 ':'");
        List<Statement> thenBody = parseStatementList(TokenType.END, TokenType.ELSE);
        List<Statement> elseBody = List.of();
        if (check(TokenType.ELSE)) {
            advance();
            if (check(TokenType.IF)) {
                // else if 链：内层 if 作为 else 分支里唯一的语句，共用外层的 end
                elseBody = List.of(parseIfStatement(false));
            } else {
                expect(TokenType.COLON, "else 后的 ':'");
                elseBody = parseStatementList(TokenType.END);
            }
        }
        if (expectEnd) {
            expect(TokenType.END, "end");
        }
        return new Statement.IfStmt(condition, thenBody, elseBody, keyword.line(), keyword.col());
    }

    private Statement parseForStatement() {
        Token keyword = expect(TokenType.FOR, "for");
        Token var = expect(TokenType.IDENTIFIER, "循环变量名");
        expect(TokenType.IN, "for 循环的 in");
        Expression iterable = parseExpression();
        expect(TokenType.COLON, "for 后的 ':'");
        List<Statement> body = parseStatementList(TokenType.END);
        expect(TokenType.END, "end");
        return new Statement.ForStmt(var.value(), iterable, body, keyword.line(), keyword.col());
    }

    private Statement parseWhileStatement() {
        Token keyword = expect(TokenType.WHILE, "while");
        Expression condition = parseExpression();
        expect(TokenType.COLON, "while 条件后的 ':'");
        List<Statement> body = parseStatementList(TokenType.END);
        expect(TokenType.END, "end");
        return new Statement.WhileStmt(condition, body, keyword.line(), keyword.col());
    }

    private Statement parseReturnStatement() {
        Token keyword = expect(TokenType.RETURN, "return");
        if (check(TokenType.RBRACE) || check(TokenType.END)) {
            return new Statement.ReturnStmt(null, keyword.line(), keyword.col());
        }
        return new Statement.ReturnStmt(parseExpression(), keyword.line(), keyword.col());
    }

    private Statement parseSimpleStatement() {
        Token start = peek();
        Expression expr = parseExpression();
        if (isAssignmentOperator(peek().type())) {
            Token opToken = advance();
            Expression value = parseExpression();
            return new Statement.AssignStmt(expr, assignOpOf(opToken), value, start.line(), start.col());
        }
        return new Statement.ExprStmt(expr, start.line(), start.col());
    }

    private static boolean isAssignmentOperator(TokenType type) {
        return type == TokenType.ASSIGN || type.isCompoundAssign();
    }

    private static Statement.AssignOp assignOpOf(Token op) {
        return switch (op.type()) {
            case ASSIGN -> Statement.AssignOp.ASSIGN;
            case PLUS_ASSIGN -> Statement.AssignOp.PLUS;
            case MINUS_ASSIGN -> Statement.AssignOp.MINUS;
            case STAR_ASSIGN -> Statement.AssignOp.STAR;
            case SLASH_ASSIGN -> Statement.AssignOp.SLASH;
            default -> throw new IllegalStateException("不是赋值运算符: " + op);
        };
    }

    // ------------------------------------------------------------------ 表达式

    public Expression parseExpression() {
        return parsePipe();
    }

    private Expression parsePipe() {
        Expression left = parseOr();
        while (check(TokenType.PIPE)) {
            Token pipe = advance();
            Expression right = parsePipeOperand();
            left = new Expression.PipeExpr(left, right, pipe.line(), pipe.col());
        }
        return left;
    }

    /** 管道右侧：允许 lambda，也允许普通调用。 */
    private Expression parsePipeOperand() {
        if (looksLikeLambda()) {
            return parseLambda();
        }
        return parseOr();
    }

    private Expression parseOr() {
        Expression left = parseCoalesce();
        while (check(TokenType.OR)) {
            Token op = advance();
            Expression right = parseCoalesce();
            left = new Expression.BinaryExpr("OR", left, right, op.line(), op.col());
        }
        return left;
    }

    private Expression parseCoalesce() {
        Expression left = parseAnd();
        while (check(TokenType.QUESTION_QUESTION)) {
            Token op = advance();
            Expression right = parseAnd();
            left = new Expression.BinaryExpr("??", left, right, op.line(), op.col());
        }
        return left;
    }

    private Expression parseAnd() {
        Expression left = parseNot();
        while (check(TokenType.AND)) {
            Token op = advance();
            Expression right = parseNot();
            left = new Expression.BinaryExpr("AND", left, right, op.line(), op.col());
        }
        return left;
    }

    private Expression parseNot() {
        if (check(TokenType.NOT)) {
            Token op = advance();
            return new Expression.UnaryExpr("NOT", parseNot(), op.line(), op.col());
        }
        return parseComparison();
    }

    private Expression parseComparison() {
        Expression left = parseRange();
        while (COMPARISON_OPS.contains(peek().type())) {
            Token op = advance();
            Expression right = parseRange();
            String symbol = op.type() == TokenType.NOT_IN ? "not in" : op.value();
            left = new Expression.BinaryExpr(symbol, left, right, op.line(), op.col());
        }
        return left;
    }

    private Expression parseRange() {
        Expression left = parseAdditive();
        if (check(TokenType.DOT_DOT)) {
            Token op = advance();
            Expression right = parseAdditive();
            return new Expression.RangeExpr(left, right, op.line(), op.col());
        }
        return left;
    }

    private Expression parseAdditive() {
        Expression left = parseMultiplicative();
        while (check(TokenType.PLUS) || check(TokenType.MINUS)) {
            Token op = advance();
            Expression right = parseMultiplicative();
            left = new Expression.BinaryExpr(op.value(), left, right, op.line(), op.col());
        }
        return left;
    }

    private Expression parseMultiplicative() {
        Expression left = parseUnary();
        while (check(TokenType.STAR) || check(TokenType.SLASH) || check(TokenType.PERCENT)) {
            Token op = advance();
            Expression right = parseUnary();
            left = new Expression.BinaryExpr(op.value(), left, right, op.line(), op.col());
        }
        return left;
    }

    private Expression parseUnary() {
        if (check(TokenType.MINUS) || check(TokenType.PLUS)) {
            Token op = advance();
            return new Expression.UnaryExpr(op.value(), parseUnary(), op.line(), op.col());
        }
        return parsePower();
    }

    private Expression parsePower() {
        Expression base = parsePostfix();
        if (check(TokenType.CARET)) {
            Token op = advance();
            // 幂运算右结合：2^3^2 == 2^(3^2)
            return new Expression.BinaryExpr("^", base, parseUnary(), op.line(), op.col());
        }
        return base;
    }

    private Expression parsePostfix() {
        Expression expr = parsePrimary();
        while (true) {
            if (check(TokenType.LPAREN)) {
                Token paren = advance();
                List<Expression.Arg> args = parseArguments();
                if (expr instanceof Expression.Identifier id) {
                    expr = new Expression.CallExpr(id.name(), args, id.line(), id.col());
                } else if (expr instanceof Expression.MemberAccess ma) {
                    expr = new Expression.MethodCall(ma.target(), ma.member(), args, ma.line(), ma.col());
                } else {
                    throw error("只有命名函数与宿主方法可以被调用，实际为 " + expr.getClass().getSimpleName(),
                            paren.line(), paren.col());
                }
            } else if (check(TokenType.LBRACKET)) {
                Token bracket = advance();
                Expression index = parseExpression();
                expect(TokenType.RBRACKET, "]");
                expr = new Expression.IndexAccess(expr, index, bracket.line(), bracket.col());
            } else if (check(TokenType.DOT)) {
                Token dot = advance();
                Token member = expect(TokenType.IDENTIFIER, "成员名");
                expr = new Expression.MemberAccess(expr, member.value(), dot.line(), dot.col());
            } else {
                break;
            }
        }
        return expr;
    }

    private List<Expression.Arg> parseArguments() {
        List<Expression.Arg> args = new ArrayList<>();
        if (check(TokenType.RPAREN)) {
            advance();
            return args;
        }
        while (true) {
            args.add(parseArgument());
            if (check(TokenType.COMMA)) {
                advance();
                continue;
            }
            break;
        }
        expect(TokenType.RPAREN, ")");
        return args;
    }

    private Expression.Arg parseArgument() {
        // 具名实参：ident = expr（文档 §2.4.7 的 emit_alert(type = "...", ...)）
        if (check(TokenType.IDENTIFIER) && peek(1).type() == TokenType.ASSIGN) {
            Token name = advance();
            advance();
            return new Expression.Arg(name.value(), parseExpression());
        }
        return new Expression.Arg(null, parseExpression());
    }

    private Expression parsePrimary() {
        Token t = peek();
        switch (t.type()) {
            case INT_LITERAL, FLOAT_LITERAL -> {
                return parseNumberLiteral();
            }
            case STRING_LITERAL -> {
                advance();
                return new Expression.StringLiteral(t.value(), t.line(), t.col());
            }
            case FSTRING_LITERAL -> {
                advance();
                return parseInterpolatedString(t);
            }
            case TRUE -> {
                advance();
                return new Expression.BoolLiteral(true, t.line(), t.col());
            }
            case FALSE -> {
                advance();
                return new Expression.BoolLiteral(false, t.line(), t.col());
            }
            case NULL -> {
                advance();
                return new Expression.NullLiteral(t.line(), t.col());
            }
            case LOW, MEDIUM, HIGH, CRITICAL -> {
                advance();
                return new Expression.SeverityLiteral(Severity.fromKeyword(t.value()), t.line(), t.col());
            }
            case IDENTIFIER -> {
                if (peek(1).type() == TokenType.ARROW) {
                    advance();
                    return parseLambdaWithParams(List.of(t.value()), t.line(), t.col());
                }
                advance();
                return new Expression.Identifier(t.value(), t.line(), t.col());
            }
            // 标准库和宿主函数名与关键字撞车时按函数名处理：
            // 文档 §2.4.3 的 map/list/set、§2.4.7 的 emit_alert/record_evidence/log/... 都是这种。
            // 表达式位置出现类型关键字不可能是类型引用（类型只出现在 input 与 let 的类型标注里），
            // 所以这里统一当标识符，交给后续的调用解析。
            // 元数据关键字同理：规则头部靠关键字自识别，但 §2.2.5 的 `severity = critical` 是把
            // severity 当普通变量用，进到 then 块里就该按标识符解析。
            case TYPE_INT, TYPE_FLOAT, TYPE_BOOL, TYPE_STRING, TYPE_LIST, TYPE_MAP, TYPE_SET, TYPE_TUPLE,
                 EMIT_ALERT, RECORD_EVIDENCE, TRIGGER_REDSCREEN, LOG, BAN_FEATURE,
                 VERSION, AUTHOR, SEVERITY, CATEGORY, COOLDOWN, ENABLED, DESCRIPTION -> {
                advance();
                return new Expression.Identifier(t.value(), t.line(), t.col());
            }
            case LPAREN -> {
                if (looksLikeParenLambda()) {
                    return parseParenLambda();
                }
                return parseParenthesizedOrTuple();
            }
            case LBRACKET -> {
                advance();
                List<Expression> elements = parseExpressionList(TokenType.RBRACKET);
                expect(TokenType.RBRACKET, "]");
                return new Expression.ListLiteral(elements, t.line(), t.col());
            }
            case LBRACE -> {
                return parseBraceLiteral();
            }
            case IF -> {
                return parseIfExpression();
            }
            default -> throw error("需要表达式，实际为 " + t);
        }
    }

    private Expression parseNumberLiteral() {
        Token t = peek();
        if (t.hasUnit() || isFollowedByUnit()) {
            return parseUnitLiteral("数值字面量");
        }
        advance();
        if (t.type() == TokenType.FLOAT_LITERAL) {
            return new Expression.FloatLiteral(parseDouble(t), t.line(), t.col());
        }
        return new Expression.IntLiteral(parseLong(t), t.line(), t.col());
    }

    /** 数值与单位之间是否有空白（{@code 5 per_second}、{@code 300 s}）。 */
    private boolean isFollowedByUnit() {
        return peek(1).type().isUnit();
    }

    private static String unitTextOf(TokenType type) {
        return switch (type) {
            case MS -> "ms";
            case S -> "s";
            case M -> "m";
            case H -> "h";
            case KB -> "kb";
            case MB -> "mb";
            case GB -> "gb";
            case PER_SECOND -> "per_second";
            case PER_MINUTE -> "per_minute";
            default -> throw new IllegalStateException("不是单位 token: " + type);
        };
    }

    private static UnitKind unitKindOf(String unit) {
        return switch (unit) {
            case "ms", "s", "m", "h" -> UnitKind.TIME;
            case "kb", "mb", "gb" -> UnitKind.DATA;
            case "per_second", "per_minute" -> UnitKind.RATE;
            default -> throw new IllegalStateException("未知单位: " + unit);
        };
    }

    private static double rawNumericValue(Token t) {
        String text = t.value().replace("_", "");
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return Long.parseLong(text.substring(2), 16);
        }
        if (text.startsWith("0b") || text.startsWith("0B")) {
            return Long.parseLong(text.substring(2), 2);
        }
        return Double.parseDouble(text);
    }

    private static double parseDouble(Token t) {
        return Double.parseDouble(t.value().replace("_", ""));
    }

    private static long parseLong(Token t) {
        String text = t.value().replace("_", "");
        if (text.startsWith("0x") || text.startsWith("0X")) {
            return Long.parseLong(text.substring(2), 16);
        }
        if (text.startsWith("0b") || text.startsWith("0B")) {
            return Long.parseLong(text.substring(2), 2);
        }
        return Long.parseLong(text);
    }

    /**
     * 解析 f-string：按 {@code {expr}} 切分，字面量片段变成 {@link Expression.StringLiteral}。
     * {@code {{}} 表示字面量花括号，与通用 f-string 语义一致。
     */
    private Expression parseInterpolatedString(Token t) {
        List<Expression> parts = new ArrayList<>();
        String text = t.value();
        StringBuilder literal = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '{' && i + 1 < text.length() && text.charAt(i + 1) == '{') {
                literal.append('{');
                i += 2;
            } else if (c == '}' && i + 1 < text.length() && text.charAt(i + 1) == '}') {
                literal.append('}');
                i += 2;
            } else if (c == '{') {
                int end = findClosingBrace(text, i + 1);
                if (end < 0) {
                    throw error("f-string 插值缺少 '}'", t.line(), t.col());
                }
                if (!literal.isEmpty()) {
                    parts.add(new Expression.StringLiteral(literal.toString(), t.line(), t.col()));
                    literal.setLength(0);
                }
                String fragment = text.substring(i + 1, end);
                parts.add(Parser.parseExpressionFragment(fragment, t.line(), t.col() + i + 1));
                i = end + 1;
            } else if (c == '}') {
                throw error("f-string 中出现未配对的 '}'（字面量花括号请写 '}}'）", t.line(), t.col());
            } else {
                literal.append(c);
                i++;
            }
        }
        if (!literal.isEmpty()) {
            parts.add(new Expression.StringLiteral(literal.toString(), t.line(), t.col()));
        }
        return new Expression.InterpolatedString(parts, t.line(), t.col());
    }

    private static int findClosingBrace(String text, int from) {
        int depth = 1;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private Expression parseParenthesizedOrTuple() {
        Token open = expect(TokenType.LPAREN, "(");
        Expression first = parseExpression();
        if (!check(TokenType.COMMA)) {
            expect(TokenType.RPAREN, ")");
            return first;
        }
        List<Expression> elements = new ArrayList<>();
        elements.add(first);
        while (check(TokenType.COMMA)) {
            advance();
            if (check(TokenType.RPAREN)) {
                break;
            }
            elements.add(parseExpression());
        }
        expect(TokenType.RPAREN, ")");
        return new Expression.TupleLiteral(elements, open.line(), open.col());
    }

    /**
     * {@code {}} 在表达式位置既可能是映射表也可能是集合：解析首个元素后看是不是 {@code key: value}。
     */
    private Expression parseBraceLiteral() {
        Token open = expect(TokenType.LBRACE, "{");
        if (check(TokenType.RBRACE)) {
            advance();
            return new Expression.MapLiteral(List.of(), open.line(), open.col());
        }
        Expression first = parseExpression();
        if (check(TokenType.COLON)) {
            List<Expression.MapEntry> entries = new ArrayList<>();
            advance();
            entries.add(new Expression.MapEntry(first, parseExpression()));
            while (check(TokenType.COMMA)) {
                advance();
                if (check(TokenType.RBRACE)) {
                    break;
                }
                Expression key = parseExpression();
                expect(TokenType.COLON, "映射表条目的 ':'");
                entries.add(new Expression.MapEntry(key, parseExpression()));
            }
            expect(TokenType.RBRACE, "}");
            return new Expression.MapLiteral(entries, open.line(), open.col());
        }
        List<Expression> elements = new ArrayList<>();
        elements.add(first);
        while (check(TokenType.COMMA)) {
            advance();
            if (check(TokenType.RBRACE)) {
                break;
            }
            elements.add(parseExpression());
        }
        expect(TokenType.RBRACE, "}");
        return new Expression.SetLiteral(elements, open.line(), open.col());
    }

    private List<Expression> parseExpressionList(TokenType terminator) {
        List<Expression> elements = new ArrayList<>();
        if (check(terminator)) {
            return elements;
        }
        while (true) {
            elements.add(parseExpression());
            if (check(TokenType.COMMA)) {
                advance();
                if (check(terminator)) {
                    break;
                }
                continue;
            }
            break;
        }
        return elements;
    }

    private Expression parseIfExpression() {
        Token keyword = expect(TokenType.IF, "if");
        Expression condition = parseExpression();
        expect(TokenType.COLON, "if 条件后的 ':'");
        Expression thenExpr = parseExpression();
        expect(TokenType.ELSE, "表达式形式的 if 需要 else 分支");
        expect(TokenType.COLON, "else 后的 ':'");
        Expression elseExpr = parseExpression();
        expect(TokenType.END, "end");
        return new Expression.IfExpr(condition, thenExpr, elseExpr, keyword.line(), keyword.col());
    }

    // ------------------------------------------------------------------ lambda

    private boolean looksLikeLambda() {
        if (check(TokenType.IDENTIFIER) && peek(1).type() == TokenType.ARROW) {
            return true;
        }
        return looksLikeParenLambda();
    }

    /** 形如 {@code (a, b) -> expr} 的 lambda 参数列表。 */
    private boolean looksLikeParenLambda() {
        if (!check(TokenType.LPAREN)) {
            return false;
        }
        int i = pos + 1;
        if (i < tokens.size() && tokens.get(i).type() == TokenType.RPAREN) {
            i++;
        } else {
            while (i < tokens.size() && tokens.get(i).type() == TokenType.IDENTIFIER) {
                i++;
                if (i < tokens.size() && tokens.get(i).type() == TokenType.COMMA) {
                    i++;
                    continue;
                }
                break;
            }
            if (i >= tokens.size() || tokens.get(i).type() != TokenType.RPAREN) {
                return false;
            }
            i++;
        }
        return i < tokens.size() && tokens.get(i).type() == TokenType.ARROW;
    }

    private Expression parseLambda() {
        Token t = peek();
        if (t.type() == TokenType.IDENTIFIER) {
            advance();
            return parseLambdaWithParams(List.of(t.value()), t.line(), t.col());
        }
        return parseParenLambda();
    }

    private Expression parseParenLambda() {
        Token open = expect(TokenType.LPAREN, "(");
        List<String> params = new ArrayList<>();
        if (!check(TokenType.RPAREN)) {
            params.add(expect(TokenType.IDENTIFIER, "lambda 参数名").value());
            while (check(TokenType.COMMA)) {
                advance();
                params.add(expect(TokenType.IDENTIFIER, "lambda 参数名").value());
            }
        }
        expect(TokenType.RPAREN, ")");
        return parseLambdaWithParams(params, open.line(), open.col());
    }

    private Expression parseLambdaWithParams(List<String> params, int line, int col) {
        expect(TokenType.ARROW, "->");
        Expression body = parseExpression();
        return new Expression.LambdaExpr(params, body, line, col);
    }

    // ------------------------------------------------------------------ token 工具

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peek(int offset) {
        int at = pos + offset;
        return at < tokens.size() ? tokens.get(at) : tokens.get(tokens.size() - 1);
    }

    private boolean check(TokenType type) {
        return peek().type() == type;
    }

    private Token advance() {
        Token t = peek();
        if (t.type() != TokenType.EOF) {
            pos++;
        }
        return t;
    }

    private Token expect(TokenType type, String what) {
        Token t = peek();
        if (t.type() != type) {
            throw error("需要 " + what + "，实际为 " + t);
        }
        return advance();
    }

    /**
     * 声明位置的变量名。
     *
     * <p>元数据关键字也允许（{@code let severity = "low"}）：规则头部靠关键字自识别，到了
     * {@code let} 后面就是普通名字，和 {@link #parsePrimary} 把元数据关键字当标识符的处理保持一致，
     * 否则 §2.2.5 里 {@code severity = critical} 这种写法没法把变量声明出来。</p>
     */
    private Token expectVariableName() {
        Token t = peek();
        if (t.type() != TokenType.IDENTIFIER && !METADATA_KEYS.contains(t.type())) {
            throw error("需要变量名，实际为 " + t);
        }
        return advance();
    }

    private ParseException error(String message) {
        Token t = peek();
        return new ParseException(message, t.line(), t.col());
    }

    private ParseException error(String message, int line, int col) {
        return new ParseException(message, line, col);
    }
}