package com.potatotv.prl.analysis;

import com.potatotv.prl.ast.Expression;
import com.potatotv.prl.ast.LetDecl;
import com.potatotv.prl.ast.Rule;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.ast.Statement;
import com.potatotv.prl.check.Diagnostic;
import com.potatotv.prl.check.TypeCheckResult;
import com.potatotv.prl.compiler.CompileResult;
import com.potatotv.prl.compiler.PrlCompiler;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.types.PrlType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 静态分析器（设计文档 §2.14）。
 *
 * <p>走一遍 AST，产出四类结论。类型错误与未使用变量不在这里重新算 —— 类型检查器（§2.14.1 的第一行）
 * 已经做过，分析器把它的诊断原样合并进来，避免两处判据漂移。</p>
 *
 * <p>几条判据都刻意做得「宁可漏报也不要误报」，因为它们直接决定规则能不能发布：</p>
 * <ul>
 *   <li><b>无限循环</b>只认一种形态：{@code while true:}（或 {@code while 1 == 1} 这种恒真字面量），
 *       且循环体里没有 {@code return}。语言里没有 {@code break}（§2.2.5），所以这一条是可证明的；
 *       其余「条件永真但看不出来」的写法一律放过。</li>
 *   <li><b>可能的空指针</b>只认两种来源：映射表按下标取值（键不存在时为 null）、
 *       {@code first}/{@code last}（空集合时为 null）。被 {@code ??} 兜住的左值不算。</li>
 *   <li><b>规则冲突</b>只认「{@code when} 条件规范化后完全相同、告警类型不同」，见 {@link RuleConflict}。</li>
 *   <li><b>安全审计</b>只查一份固定名单（动态执行、文件、网络、反射）。这些名字类型检查器也会判成
 *       未知函数，这里再报一条是为了让管理端能说清「沙箱明确禁止」而不是「你是不是拼错了」。</li>
 * </ul>
 */
public final class PrlAnalyzer {

    /** 可能的空指针。 */
    public static final String NULL_RISK_CODE = "PRL-N";

    /** 可证明的无限循环。 */
    public static final String INFINITE_LOOP_CODE = "PRL-L";

    /** 安全审计。 */
    public static final String SECURITY_CODE = "PRL-S";

    /**
     * 规则冲突（§2.14.2）。
     *
     * <p>不走 {@link com.potatotv.prl.check.Diagnostic}：冲突判据分不出「{@code SUSPICIOUS} 与
     * {@code NORMAL}」和「两个都合法但名字不同的类型」，只配报建议，不配报错。它出现在
     * {@link AnalysisResult#report()} 的冲突行里，管理端按这个码做分类统计。</p>
     */
    public static final String CONFLICT_CODE = "PRL-C";

    /** 沙箱明确禁止的函数（§2.11.1 L1/L3、§2.18.2）。 */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            "loadstring", "require", "eval", "exec", "system", "shell", "popen",
            "file_read", "file_write", "file_delete", "open_file",
            "http_get", "http_post", "socket", "connect", "dns_query",
            "getenv", "setenv", "class_for_name", "new_instance", "os_clock", "os_time");

    /** 沙箱明确禁止的成员（反射与进程相关）。 */
    private static final Set<String> FORBIDDEN_MEMBERS = Set.of(
            "getclass", "class", "classloader", "forname", "newinstance", "runtime", "exec", "exit");

    /** 可能返回 null 的标准库函数（§2.4.3 集合操作）。 */
    private static final Set<String> NULLABLE_FUNCTIONS = Set.of("first", "last", "get");

    /** 静态估算里「一条字节码」的时间成本；系数来源见 {@link RuleMetrics#estimatedNanos()}。 */
    private static final long NANOS_PER_INSTRUCTION = 10L;

    private static final int BYTES_PER_SLOT = 64;
    private static final int BYTES_PER_ELEMENT = 24;
    private static final int BYTES_PER_FRAME = 256;

    private final PrlCompiler compiler;

    public PrlAnalyzer() {
        this(PrlHostContext.EMPTY);
    }

    public PrlAnalyzer(PrlHostContext host) {
        this.compiler = new PrlCompiler(host);
    }

    /** 从源码开始分析：编译一次，失败也照常分析（编辑器要在有错的情况下继续给提示）。 */
    public AnalysisResult analyze(String source) {
        return analyze(compiler.compileChecked(source));
    }

    public AnalysisResult analyze(CompileResult compile) {
        if (compile.file() == null) {
            return new AnalysisResult(compile, compile.diagnostics(), List.of(), List.of());
        }
        RuleFile file = compile.file();
        List<Diagnostic> diagnostics = new ArrayList<>(compile.diagnostics());
        List<RuleMetrics> metrics = new ArrayList<>(file.rules().size());
        for (Rule rule : file.rules()) {
            Visitor visitor = new Visitor(compile.types(), diagnostics);
            visitor.visitRule(rule);
            metrics.add(visitor.metrics(rule.name()));
        }
        return new AnalysisResult(compile, diagnostics, metrics, detectConflicts(file));
    }

    // ------------------------------------------------------------------ 冲突检测

    /**
     * §2.14.2 的规则冲突：{@code when} 条件规范化后相同，告警类型却不同。
     *
     * <p>条件是逐字比对而不是判断语义重叠。判断「{@code rate > 8} 与 {@code rate > 5} 有包含关系」
     * 需要区间求解，做不准就会在管理端刷出一屏无用告警；逐字比对能稳准地抓住复制粘贴型冲突，
     * 这也是实际发生最多的一类。</p>
     */
    public static List<RuleConflict> detectConflicts(RuleFile file) {
        Map<String, List<Rule>> byCondition = new TreeMap<>();
        for (Rule rule : file.rules()) {
            if (rule.when() == null) {
                continue;
            }
            byCondition.computeIfAbsent(canonical(rule.when().condition()), key -> new ArrayList<>())
                    .add(rule);
        }
        List<RuleConflict> conflicts = new ArrayList<>();
        for (Map.Entry<String, List<Rule>> entry : byCondition.entrySet()) {
            List<Rule> group = entry.getValue();
            for (int i = 0; i < group.size(); i++) {
                for (int j = i + 1; j < group.size(); j++) {
                    RuleConflict conflict = compare(group.get(i), group.get(j), entry.getKey());
                    if (conflict != null) {
                        conflicts.add(conflict);
                    }
                }
            }
        }
        return conflicts;
    }

    private static RuleConflict compare(Rule a, Rule b, String condition) {
        String typeA = alertType(a);
        String typeB = alertType(b);
        if (typeA != null && typeB != null && !typeA.equals(typeB)) {
            return new RuleConflict(a.name(), b.name(), condition,
                    "触发条件相同但告警类型不同（" + typeA + " / " + typeB + "）",
                    "合并规则，或调整阈值让两条规则的适用区间错开");
        }
        if (typeA != null && typeA.equals(typeB)) {
            return new RuleConflict(a.name(), b.name(), condition,
                    "触发条件与告警类型都相同（" + typeA + "）",
                    "两条规则会重复告警，删掉其中一条");
        }
        return null;
    }

    /** 规则里第一条 {@code emit_alert} 的告警类型；取不到返回 {@code null}。 */
    private static String alertType(Rule rule) {
        for (Statement statement : rule.then()) {
            String type = alertTypeIn(statement);
            if (type != null) {
                return type;
            }
        }
        return null;
    }

    private static String alertTypeIn(Statement statement) {
        if (statement instanceof Statement.ExprStmt exprStmt
                && exprStmt.expression() instanceof Expression.CallExpr call
                && "emit_alert".equals(call.name())) {
            for (Expression.Arg arg : call.args()) {
                if ("type".equals(arg.name()) && arg.value() instanceof Expression.StringLiteral literal) {
                    return literal.value();
                }
            }
            for (Expression.Arg arg : call.args()) {
                if (arg.name() == null && arg.value() instanceof Expression.StringLiteral literal) {
                    return literal.value();
                }
            }
            return null;
        }
        if (statement instanceof Statement.IfStmt ifStmt) {
            for (Statement inner : ifStmt.thenBody()) {
                String type = alertTypeIn(inner);
                if (type != null) {
                    return type;
                }
            }
            for (Statement inner : ifStmt.elseBody()) {
                String type = alertTypeIn(inner);
                if (type != null) {
                    return type;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 规范化打印

    /**
     * 把表达式打印成与空白、具名实参书写顺序无关的规范文本。
     *
     * <p>具名实参按名字排序：{@code emit_alert(type = "X", confidence = 0.9)} 与
     * {@code emit_alert(confidence = 0.9, type = "X")} 是同一件事，不排序就会漏掉真冲突。</p>
     */
    static String canonical(Expression expression) {
        if (expression == null) {
            return "null";
        }
        return switch (expression) {
            case Expression.IntLiteral literal -> Long.toString(literal.value());
            case Expression.FloatLiteral literal -> trimNumber(literal.value());
            case Expression.StringLiteral literal -> '"' + literal.value() + '"';
            case Expression.InterpolatedString literal -> "f\"" + join(literal.parts()) + '"';
            case Expression.BoolLiteral literal -> Boolean.toString(literal.value());
            case Expression.NullLiteral ignored -> "null";
            case Expression.SeverityLiteral literal -> literal.value().name().toLowerCase(Locale.ROOT);
            case Expression.UnitLiteral literal -> trimNumber(literal.magnitude()) + literal.unitText();
            case Expression.Identifier literal -> literal.name();
            case Expression.BinaryExpr binary -> "(" + canonical(binary.left()) + " " + binary.op() + " "
                    + canonical(binary.right()) + ")";
            case Expression.UnaryExpr unary -> "(" + unary.op() + " " + canonical(unary.operand()) + ")";
            case Expression.CallExpr call -> call.name() + "(" + canonicalArgs(call.args()) + ")";
            case Expression.MethodCall call -> canonical(call.receiver()) + "." + call.name()
                    + "(" + canonicalArgs(call.args()) + ")";
            case Expression.MemberAccess member -> canonical(member.target()) + "." + member.member();
            case Expression.IndexAccess index -> canonical(index.target()) + "[" + canonical(index.index()) + "]";
            case Expression.LambdaExpr lambda -> "(" + String.join(",", lambda.params()) + ") -> "
                    + canonical(lambda.body());
            case Expression.ListLiteral list -> "[" + join(list.elements()) + "]";
            case Expression.MapLiteral map -> "{" + joinEntries(map.entries()) + "}";
            case Expression.SetLiteral set -> "{" + join(set.elements()) + "}";
            case Expression.TupleLiteral tuple -> "(" + join(tuple.elements()) + ")";
            case Expression.RangeExpr range -> canonical(range.start()) + ".." + canonical(range.end());
            case Expression.PipeExpr pipe -> canonical(pipe.left()) + " |> " + canonical(pipe.right());
            case Expression.IfExpr ifExpr -> "(" + canonical(ifExpr.condition()) + " ? "
                    + canonical(ifExpr.thenExpr()) + " : " + canonical(ifExpr.elseExpr()) + ")";
        };
    }

    private static String canonicalArgs(List<Expression.Arg> args) {
        List<String> positional = new ArrayList<>();
        Map<String, String> named = new TreeMap<>();
        for (Expression.Arg arg : args) {
            if (arg.name() == null) {
                positional.add(canonical(arg.value()));
            } else {
                named.put(arg.name(), canonical(arg.value()));
            }
        }
        positional.addAll(named.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList());
        return String.join(",", positional);
    }

    private static String joinEntries(List<Expression.MapEntry> entries) {
        return String.join(",", entries.stream()
                .map(entry -> canonical(entry.key()) + ":" + canonical(entry.value()))
                .toList());
    }

    private static String join(List<Expression> parts) {
        return String.join(",", parts.stream().map(PrlAnalyzer::canonical).toList());
    }

    /** {@code 1.0} 与 {@code 1} 在源码里写法不同、语义相同，规范化后统一成最短写法。 */
    private static String trimNumber(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    // ------------------------------------------------------------------ AST 巡查

    private static final class Visitor {

        private final TypeCheckResult types;
        private final List<Diagnostic> out;

        private int decisions;
        private int nodes;
        private int slots;
        private int elements;
        private int depth;
        private int maxDepth;

        Visitor(TypeCheckResult types, List<Diagnostic> out) {
            this.types = types;
            this.out = out;
        }

        RuleMetrics metrics(String ruleName) {
            long instructions = Math.max(nodes, 1);
            long bytes = BYTES_PER_FRAME + (long) slots * BYTES_PER_SLOT + (long) elements * BYTES_PER_ELEMENT;
            int score = Math.min(100, decisions * 4 + maxDepth * 6);
            return new RuleMetrics(ruleName, decisions, maxDepth, (int) instructions,
                    instructions * NANOS_PER_INSTRUCTION, bytes, score);
        }

        void visitRule(Rule rule) {
            nodes++;
            slots += rule.input().fields().size();
            if (rule.when() != null) {
                // when 是一次判定，算进圈复杂度：多一条判据就多一个分支。
                decisions++;
                expr(rule.when().condition(), false);
            }
            for (LetDecl let : rule.lets()) {
                nodes++;
                slots++;
                expr(let.initializer(), false);
            }
            statements(rule.then());
        }

        private void statements(List<Statement> body) {
            for (Statement statement : body) {
                statement(statement);
            }
        }

        private void statement(Statement statement) {
            nodes++;
            switch (statement) {
                case LetDecl let -> {
                    slots++;
                    expr(let.initializer(), false);
                }
                case Statement.AssignStmt assign -> {
                    expr(assign.target(), false);
                    expr(assign.value(), false);
                }
                case Statement.IfStmt ifStmt -> {
                    decisions++;
                    expr(ifStmt.condition(), false);
                    nested(ifStmt.thenBody());
                    nested(ifStmt.elseBody());
                }
                case Statement.ForStmt forStmt -> {
                    decisions++;
                    expr(forStmt.iterable(), false);
                    slots++;
                    nested(forStmt.body());
                }
                case Statement.WhileStmt whileStmt -> {
                    decisions++;
                    expr(whileStmt.condition(), false);
                    checkInfiniteLoop(whileStmt);
                    nested(whileStmt.body());
                }
                case Statement.ReturnStmt returnStmt -> expr(returnStmt.value(), false);
                case Statement.ExprStmt exprStmt -> expr(exprStmt.expression(), false);
            }
        }

        private void nested(List<Statement> body) {
            depth++;
            maxDepth = Math.max(maxDepth, depth);
            statements(body);
            depth--;
        }

        private void checkInfiniteLoop(Statement.WhileStmt loop) {
            if (!isConstantTrue(loop.condition())) {
                return;
            }
            if (containsReturn(loop.body())) {
                return;
            }
            out.add(Diagnostic.error(INFINITE_LOOP_CODE,
                    "循环条件恒为真且循环体里没有 return，这段循环不会结束（运行时也会被指令数上限打断）",
                    loop.line(), loop.col()));
        }

        private static boolean isConstantTrue(Expression condition) {
            if (condition instanceof Expression.BoolLiteral literal) {
                return literal.value();
            }
            if (condition instanceof Expression.BinaryExpr binary) {
                // 1 == 1 / "a" == "a" 这类恒真写法：两侧都是字面量且运算符是等值比较。
                boolean equality = "==".equals(binary.op()) || "EQ".equals(binary.op());
                return equality && isLiteral(binary.left()) && isLiteral(binary.right())
                        && canonical(binary.left()).equals(canonical(binary.right()));
            }
            return false;
        }

        private static boolean isLiteral(Expression expression) {
            return expression instanceof Expression.IntLiteral
                    || expression instanceof Expression.FloatLiteral
                    || expression instanceof Expression.StringLiteral
                    || expression instanceof Expression.BoolLiteral;
        }

        private static boolean containsReturn(List<Statement> body) {
            for (Statement statement : body) {
                if (statement instanceof Statement.ReturnStmt) {
                    return true;
                }
                if (statement instanceof Statement.IfStmt ifStmt
                        && (containsReturn(ifStmt.thenBody()) || containsReturn(ifStmt.elseBody()))) {
                    return true;
                }
                if (statement instanceof Statement.ForStmt forStmt && containsReturn(forStmt.body())) {
                    return true;
                }
                if (statement instanceof Statement.WhileStmt whileStmt && containsReturn(whileStmt.body())) {
                    return true;
                }
            }
            return false;
        }

        // -------------------------------------------------------------- 表达式

        private void expr(Expression expression, boolean guarded) {
            if (expression == null) {
                return;
            }
            nodes++;
            switch (expression) {
                case Expression.BinaryExpr binary -> {
                    if (isDecisionOperator(binary.op())) {
                        decisions++;
                    }
                    // ?? 的左值就是「允许为空」的位置，进去之后不再报空指针。
                    boolean rightGuarded = guarded || "??".equals(binary.op());
                    expr(binary.left(), rightGuarded);
                    expr(binary.right(), guarded);
                }
                case Expression.UnaryExpr unary -> expr(unary.operand(), guarded);
                case Expression.CallExpr call -> {
                    checkForbiddenFunction(call);
                    for (Expression.Arg arg : call.args()) {
                        expr(arg.value(), false);
                    }
                }
                case Expression.MethodCall call -> {
                    checkNull(call.receiver(), guarded, call.line(), call.col(), "方法调用");
                    checkForbiddenMember(call.name(), call.line(), call.col());
                    expr(call.receiver(), false);
                    for (Expression.Arg arg : call.args()) {
                        expr(arg.value(), false);
                    }
                }
                case Expression.MemberAccess member -> {
                    checkNull(member.target(), guarded, member.line(), member.col(), "成员访问");
                    checkForbiddenMember(member.member(), member.line(), member.col());
                    expr(member.target(), false);
                }
                case Expression.IndexAccess index -> {
                    checkNull(index.target(), guarded, index.line(), index.col(), "下标访问");
                    expr(index.target(), false);
                    expr(index.index(), false);
                }
                case Expression.LambdaExpr lambda -> {
                    slots += lambda.params().size();
                    expr(lambda.body(), false);
                }
                case Expression.InterpolatedString text -> {
                    for (Expression part : text.parts()) {
                        expr(part, false);
                    }
                }
                case Expression.ListLiteral list -> {
                    elements += list.elements().size();
                    for (Expression element : list.elements()) {
                        expr(element, false);
                    }
                }
                case Expression.SetLiteral set -> {
                    elements += set.elements().size();
                    for (Expression element : set.elements()) {
                        expr(element, false);
                    }
                }
                case Expression.TupleLiteral tuple -> {
                    elements += tuple.elements().size();
                    for (Expression element : tuple.elements()) {
                        expr(element, false);
                    }
                }
                case Expression.MapLiteral map -> {
                    elements += map.entries().size() * 2;
                    for (Expression.MapEntry entry : map.entries()) {
                        expr(entry.key(), false);
                        expr(entry.value(), false);
                    }
                }
                case Expression.RangeExpr range -> {
                    expr(range.start(), false);
                    expr(range.end(), false);
                }
                case Expression.PipeExpr pipe -> {
                    expr(pipe.left(), false);
                    expr(pipe.right(), false);
                }
                case Expression.IfExpr ifExpr -> {
                    decisions++;
                    expr(ifExpr.condition(), false);
                    expr(ifExpr.thenExpr(), false);
                    expr(ifExpr.elseExpr(), false);
                }
                default -> {
                    // 字面量与标识符没有子节点。
                }
            }
        }

        private static boolean isDecisionOperator(String op) {
            return "AND".equals(op) || "OR".equals(op) || "and".equals(op) || "or".equals(op);
        }

        private void checkNull(Expression target, boolean guarded, int line, int col, String what) {
            if (guarded || !possiblyNull(target)) {
                return;
            }
            out.add(Diagnostic.warning(NULL_RISK_CODE,
                    what + "的目标可能为 null（" + canonical(target) + "），建议用 ?? 兜底",
                    line, col));
        }

        private boolean possiblyNull(Expression expression) {
            if (expression instanceof Expression.CallExpr call) {
                return NULLABLE_FUNCTIONS.contains(call.name());
            }
            if (!(expression instanceof Expression.IndexAccess index)) {
                return false;
            }
            // 只有映射表取值会「键不存在 → null」；列表越界是抛异常，不在这里报。
            return kindOf(index.target()) == PrlType.Kind.MAP;
        }

        private PrlType.Kind kindOf(Expression expression) {
            if (types == null) {
                return null;
            }
            return types.typeOf(expression).map(PrlType::kind).orElse(null);
        }

        private void checkForbiddenFunction(Expression.CallExpr call) {
            String lower = call.name().toLowerCase(Locale.ROOT);
            if (FORBIDDEN_FUNCTIONS.contains(lower)) {
                out.add(Diagnostic.error(SECURITY_CODE,
                        "沙箱禁止调用 '" + call.name() + "'（§2.11.1 L1：不提供动态执行/文件/网络/系统时间的入口）",
                        call.line(), call.col()));
            }
        }

        private void checkForbiddenMember(String member, int line, int col) {
            if (FORBIDDEN_MEMBERS.contains(member.toLowerCase(Locale.ROOT))) {
                out.add(Diagnostic.error(SECURITY_CODE,
                        "沙箱禁止访问成员 '" + member + "'（§2.11.1 L1：规则不能碰宿主类与进程）", line, col));
            }
        }
    }

    /** 供管理端展示冲突列表时按规则名索引。 */
    public static Map<String, List<RuleConflict>> indexConflicts(List<RuleConflict> conflicts) {
        Map<String, List<RuleConflict>> index = new LinkedHashMap<>();
        for (RuleConflict conflict : conflicts) {
            index.computeIfAbsent(conflict.ruleA(), key -> new ArrayList<>()).add(conflict);
            index.computeIfAbsent(conflict.ruleB(), key -> new ArrayList<>()).add(conflict);
        }
        return index;
    }
}