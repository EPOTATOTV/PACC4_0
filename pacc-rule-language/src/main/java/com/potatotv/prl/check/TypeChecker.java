package com.potatotv.prl.check;

import com.potatotv.prl.ast.AstNode;
import com.potatotv.prl.ast.Expression;
import com.potatotv.prl.ast.InputDecl;
import com.potatotv.prl.ast.LetDecl;
import com.potatotv.prl.ast.Rule;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.ast.Statement;
import com.potatotv.prl.ast.TypeRef;
import com.potatotv.prl.types.HostFunction;
import com.potatotv.prl.types.HostType;
import com.potatotv.prl.types.HostTypeRegistry;
import com.potatotv.prl.types.PrlSignatures;
import com.potatotv.prl.types.PrlType;
import com.potatotv.prl.types.TypeException;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 类型检查器（设计文档 §2.3.4、§2.3.5、§2.14.1）。
 *
 * <p>一次 {@link #check(RuleFile)} 走完整棵 AST，产出两样东西：诊断列表，以及每个表达式的推断类型
 * （字节码编译器要用，见 {@link TypeCheckResult}）。</p>
 *
 * <p>几个实现上的取舍：</p>
 * <ul>
 *   <li><b>不抛异常，只收诊断。</b>类型不匹配、未知函数这类问题全部记进 {@code Diagnostic}，一路推完
 *       再一次性返回；否则管理端编辑器一次只能看到一个错。</li>
 *   <li><b>重载解析分两遍。</b>同名多签名（{@code abs}、{@code contains}、{@code min}）先静默试匹配，
 *       选中唯一候选后正式再跑一遍记录类型与诊断，避免试探过程产生重复或误报的错误。</li>
 *   <li><b>lambda 参数类型来自被调函数的签名。</b>{@code filter(list[T], (T) -> bool)} 里 {@code T} 的
 *       绑定顺序决定了 lambda 参数的推断结果，所以实参严格从左到右处理；签名里还没绑定的占位符一律
 *       退化成「未知类型」，这样 {@code e -> e.damage} 这种先声明后使用的 lambda 不会连锁报错。</li>
 *   <li><b>未使用变量在这里报</b>，因为它已经维护了作用域栈；§2.14.1 的其余分析（无限循环、性能预估、
 *       冲突检测、复杂度）在 {@code com.potatotv.prl.analysis} 里做，共用这边的诊断载体。</li>
 * </ul>
 */
public final class TypeChecker {

    private final HostTypeRegistry hostTypes;
    private final PrlSignatures signatures;

    /**
     * 宿主在 §2.11.2 {@code getAvailableFunctions()} 里声明的函数白名单。
     * {@code null} 表示不限制（脱离宿主单独编译规则，比如管理端编辑器与单测）。
     */
    private final Set<String> hostWhitelist;

    private final Map<Expression, PrlType> types = new IdentityHashMap<>();
    private final Map<Expression, int[]> callArgOrder = new IdentityHashMap<>();
    private final List<Diagnostic> diagnostics = new ArrayList<>();
    private final List<Scope> scopes = new ArrayList<>();

    /** 静默模式：只算类型、不记诊断、不记类型表，用于重载探测。 */
    private boolean silent;

    public TypeChecker() {
        this(HostTypeRegistry.standard(), PrlSignatures.standard(), null);
    }

    public TypeChecker(HostTypeRegistry hostTypes, PrlSignatures signatures, Set<String> hostWhitelist) {
        this.hostTypes = hostTypes;
        this.signatures = signatures;
        this.hostWhitelist = hostWhitelist;
    }

    // ------------------------------------------------------------------ 入口

    public TypeCheckResult check(RuleFile file) {
        types.clear();
        callArgOrder.clear();
        diagnostics.clear();
        scopes.clear();
        silent = false;

        for (Rule rule : file.rules()) {
            checkRule(rule);
        }
        return new TypeCheckResult(new IdentityHashMap<>(types), new IdentityHashMap<>(callArgOrder), diagnostics);
    }

    private void checkRule(Rule rule) {
        pushScope();
        if (rule.input() != null) {
            for (InputDecl.Field field : rule.input().fields()) {
                PrlType type = resolveType(field.type());
                if (type == null) {
                    continue;
                }
                if (currentScope().vars.containsKey(field.name())) {
                    error("重复声明的输入变量 '" + field.name() + "'", field.line(), field.col());
                    continue;
                }
                Slot slot = declare(field.name(), type, field.line(), field.col());
                slot.input = true;
            }
        }
        for (LetDecl let : rule.lets()) {
            checkLet(let);
        }
        if (rule.when() != null) {
            PrlType condition = infer(rule.when().condition());
            requireType(condition, PrlType.BOOL, "when 条件", rule.when().condition());
        }
        for (Statement statement : rule.then()) {
            checkStatement(statement);
        }
        popScope();
    }

    // ------------------------------------------------------------------ 作用域

    private static final class Slot {
        final String name;
        final PrlType type;
        final int line;
        final int col;
        boolean input;
        boolean read;
        boolean assigned;

        Slot(String name, PrlType type, int line, int col) {
            this.name = name;
            this.type = type;
            this.line = line;
            this.col = col;
        }
    }

    private static final class Scope {
        final Map<String, Slot> vars = new LinkedHashMap<>();
    }

    private void pushScope() {
        scopes.add(new Scope());
    }

    private void popScope() {
        Scope scope = scopes.remove(scopes.size() - 1);
        for (Slot slot : scope.vars.values()) {
            if (!slot.read && !slot.input && !slot.name.startsWith("_")) {
                warning("变量 '" + slot.name + "' 声明后没有被读取", slot.line, slot.col);
            }
        }
    }

    private Scope currentScope() {
        return scopes.get(scopes.size() - 1);
    }

    private Slot declare(String name, PrlType type, int line, int col) {
        Slot slot = new Slot(name, type == null ? PrlType.UNKNOWN : type, line, col);
        currentScope().vars.put(name, slot);
        return slot;
    }

    private Slot lookup(String name) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            Slot slot = scopes.get(i).vars.get(name);
            if (slot != null) {
                return slot;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 语句

    private void checkStatement(Statement statement) {
        if (statement instanceof LetDecl let) {
            checkLet(let);
        } else if (statement instanceof Statement.IfStmt ifStmt) {
            checkIf(ifStmt);
        } else if (statement instanceof Statement.ForStmt forStmt) {
            checkFor(forStmt);
        } else if (statement instanceof Statement.WhileStmt whileStmt) {
            PrlType condition = infer(whileStmt.condition());
            requireType(condition, PrlType.BOOL, "while 条件", whileStmt.condition());
            pushScope();
            for (Statement inner : whileStmt.body()) {
                checkStatement(inner);
            }
            popScope();
        } else if (statement instanceof Statement.ReturnStmt returnStmt) {
            infer(returnStmt.value());
        } else if (statement instanceof Statement.ExprStmt exprStmt) {
            // 动作函数（emit_alert 等）就是当语句用的，返回值类型不关心。
            infer(exprStmt.expression());
        } else if (statement instanceof Statement.AssignStmt assignStmt) {
            checkAssign(assignStmt);
        }
    }

    private void checkLet(LetDecl let) {
        PrlType declared = let.hasDeclaredType() ? resolveType(let.declaredType()) : null;
        PrlType inferred = infer(let.initializer());
        if (declared != null) {
            checkAssignable(declared, inferred, "变量 '" + let.name() + "' 的初始值", let);
        }
        if (currentScope().vars.containsKey(let.name())) {
            error("重复声明的变量 '" + let.name() + "'", let.line(), let.col());
        }
        declare(let.name(), declared != null ? declared : inferred, let.line(), let.col());
    }

    private void checkIf(Statement.IfStmt ifStmt) {
        PrlType condition = infer(ifStmt.condition());
        requireType(condition, PrlType.BOOL, "if 条件", ifStmt.condition());
        pushScope();
        for (Statement inner : ifStmt.thenBody()) {
            checkStatement(inner);
        }
        popScope();
        pushScope();
        for (Statement inner : ifStmt.elseBody()) {
            checkStatement(inner);
        }
        popScope();
    }

    private void checkFor(Statement.ForStmt forStmt) {
        PrlType iterable = infer(forStmt.iterable());
        PrlType element = elementOf(iterable, "for 循环的迭代对象", forStmt.iterable());
        pushScope();
        declare(forStmt.variable(), element, forStmt.line(), forStmt.col());
        for (Statement inner : forStmt.body()) {
            checkStatement(inner);
        }
        popScope();
    }

    /**
     * {@code for} 的迭代元素类型。
     *
     * <p>{@code map} 迭代的是键（文档没有写迭代方式，取键是最小惊讶的选择：想要值就 {@code m[k]}）。</p>
     */
    private PrlType elementOf(PrlType iterable, String what, AstNode at) {
        if (iterable == null || iterable.isUnknown()) {
            return PrlType.UNKNOWN;
        }
        return switch (iterable.kind()) {
            case LIST, SET -> iterable.elementType();
            case MAP -> iterable.keyType();
            case STRING -> PrlType.STRING;
            case TUPLE -> PrlType.UNKNOWN;
            default -> {
                error(what + " 需要集合类型，实际为 " + iterable.display(), at.line(), at.col());
                yield PrlType.UNKNOWN;
            }
        };
    }

    private void checkAssign(Statement.AssignStmt assign) {
        Expression target = assign.target();
        if (target instanceof Expression.Identifier id) {
            Slot slot = lookup(id.name());
            if (slot == null) {
                error("未定义的变量 '" + id.name() + "'", id.line(), id.col());
                infer(assign.value());
                return;
            }
            PrlType valueType = infer(assign.value());
            if (assign.op() == Statement.AssignOp.ASSIGN) {
                checkAssignable(slot.type, valueType, "变量 '" + id.name() + "'", assign);
            } else {
                PrlType opResult = binaryOp(assign.op().symbol().substring(0, 1), slot.type, valueType, assign);
                checkAssignable(slot.type, opResult, "变量 '" + id.name() + "'", assign);
            }
            slot.assigned = true;
        } else if (target instanceof Expression.IndexAccess index) {
            PrlType container = infer(index.target());
            PrlType key = infer(index.index());
            PrlType valueType = infer(assign.value());
            if (container != null && container.kind() == PrlType.Kind.LIST) {
                checkIndexType(key, PrlType.INT, index);
                PrlType expected = assign.op() == Statement.AssignOp.ASSIGN
                        ? container.elementType()
                        : binaryOp(assign.op().symbol().substring(0, 1), container.elementType(), valueType, assign);
                checkAssignable(container.elementType(), expected, "列表元素", assign);
            } else if (container != null && container.kind() == PrlType.Kind.MAP) {
                checkAssignable(container.keyType(), key, "map 的键", index);
                PrlType expected = assign.op() == Statement.AssignOp.ASSIGN
                        ? container.valueType()
                        : binaryOp(assign.op().symbol().substring(0, 1), container.valueType(), valueType, assign);
                checkAssignable(container.valueType(), expected, "map 的值", assign);
            } else if (container != null && !container.isUnknown()) {
                error("下标赋值只支持 list 与 map，实际为 " + container.display(), assign.line(), assign.col());
            }
        } else if (target instanceof Expression.MemberAccess member) {
            infer(member.target());
            infer(assign.value());
            // 宿主对象在规则侧只读（§2.11.1 L3「只能访问声明的 input 变量」）。
            error("不允许修改宿主对象的成员 '" + member.member() + "'", assign.line(), assign.col());
        } else {
            infer(assign.value());
            error("赋值目标只能是变量、下标或宿主成员，实际为 "
                    + target.getClass().getSimpleName(), assign.line(), assign.col());
        }
    }

    private void checkIndexType(PrlType index, PrlType expected, AstNode at) {
        if (index != null && !index.isUnknown() && !index.equals(expected)) {
            error("下标需要 " + expected.display() + "，实际为 " + index.display(), at.line(), at.col());
        }
    }

    // ------------------------------------------------------------------ 表达式（分发）

    private PrlType infer(Expression expression) {
        if (expression == null) {
            return PrlType.UNKNOWN;
        }
        PrlType type = inferInternal(expression);
        if (type == null) {
            type = PrlType.UNKNOWN;
        }
        if (!silent) {
            types.put(expression, type);
        }
        return type;
    }

    private PrlType inferInternal(Expression expression) {
        if (expression instanceof Expression.IntLiteral) {
            return PrlType.INT;
        }
        if (expression instanceof Expression.FloatLiteral) {
            return PrlType.FLOAT;
        }
        if (expression instanceof Expression.StringLiteral) {
            return PrlType.STRING;
        }
        if (expression instanceof Expression.BoolLiteral) {
            return PrlType.BOOL;
        }
        if (expression instanceof Expression.NullLiteral) {
            return PrlType.NULL;
        }
        if (expression instanceof Expression.SeverityLiteral) {
            // §2.2.5 的 `severity = critical`：严重级字面量在运行时就是它的小写字符串，
            // 写进证据 map 时不用再转换。文档没有定义独立的 severity 类型。
            return PrlType.STRING;
        }
        if (expression instanceof Expression.UnitLiteral unit) {
            return switch (unit.kind()) {
                case TIME, DATA -> PrlType.INT;
                case RATE -> PrlType.FLOAT;
            };
        }
        if (expression instanceof Expression.InterpolatedString interpolated) {
            for (Expression part : interpolated.parts()) {
                infer(part);
            }
            return PrlType.STRING;
        }
        if (expression instanceof Expression.Identifier id) {
            Slot slot = lookup(id.name());
            if (slot == null) {
                error("未定义的变量 '" + id.name() + "'", id.line(), id.col());
                return PrlType.UNKNOWN;
            }
            slot.read = true;
            return slot.type;
        }
        if (expression instanceof Expression.BinaryExpr binary) {
            PrlType left = infer(binary.left());
            PrlType right = infer(binary.right());
            return binaryOp(binary.op(), left, right, binary);
        }
        if (expression instanceof Expression.UnaryExpr unary) {
            return unaryOp(unary);
        }
        if (expression instanceof Expression.CallExpr call) {
            return resolveCall(call, null);
        }
        if (expression instanceof Expression.MethodCall methodCall) {
            return inferMethodCall(methodCall);
        }
        if (expression instanceof Expression.MemberAccess memberAccess) {
            return inferMemberAccess(memberAccess);
        }
        if (expression instanceof Expression.IndexAccess indexAccess) {
            return inferIndexAccess(indexAccess);
        }
        if (expression instanceof Expression.LambdaExpr lambda) {
            return inferLambda(lambda, null);
        }
        if (expression instanceof Expression.ListLiteral list) {
            return inferListLiteral(list);
        }
        if (expression instanceof Expression.MapLiteral map) {
            return inferMapLiteral(map);
        }
        if (expression instanceof Expression.SetLiteral set) {
            return inferSetLiteral(set);
        }
        if (expression instanceof Expression.TupleLiteral tuple) {
            List<PrlType> elements = new ArrayList<>(tuple.elements().size());
            for (Expression element : tuple.elements()) {
                elements.add(infer(element));
            }
            return PrlType.tuple(elements);
        }
        if (expression instanceof Expression.RangeExpr range) {
            PrlType start = infer(range.start());
            PrlType end = infer(range.end());
            requireType(start, PrlType.INT, "范围起点", range.start());
            requireType(end, PrlType.INT, "范围终点", range.end());
            return PrlType.list(PrlType.INT);
        }
        if (expression instanceof Expression.PipeExpr pipe) {
            return inferPipe(pipe);
        }
        if (expression instanceof Expression.IfExpr ifExpr) {
            return inferIfExpr(ifExpr);
        }
        return PrlType.UNKNOWN;
    }

    // ------------------------------------------------------------------ 运算符

    private PrlType binaryOp(String op, PrlType left, PrlType right, AstNode at) {
        if (left == null || right == null) {
            return PrlType.UNKNOWN;
        }
        if (left.isUnknown() || right.isUnknown()) {
            return PrlType.UNKNOWN;
        }
        switch (op) {
            case "+":
                if (left.isNumeric() && left.equals(right)) {
                    return left;
                }
                if (left.kind() == PrlType.Kind.STRING && right.kind() == PrlType.Kind.STRING) {
                    return PrlType.STRING;
                }
                if (left.kind() == PrlType.Kind.LIST && right.kind() == PrlType.Kind.LIST) {
                    PrlType merged = mergeOrNull(left.elementType(), right.elementType());
                    return PrlType.list(merged == null ? PrlType.UNKNOWN : merged);
                }
                break;
            case "-", "*", "/", "%", "^":
                if (left.isNumeric() && left.equals(right)) {
                    return left;
                }
                break;
            case "AND", "OR":
                if (left.kind() == PrlType.Kind.BOOL && right.kind() == PrlType.Kind.BOOL) {
                    return PrlType.BOOL;
                }
                break;
            case "??":
                if (left.kind() == PrlType.Kind.NULL) {
                    return right;
                }
                return left;
            case "==", "!=":
                if (isComparable(left, right)) {
                    return PrlType.BOOL;
                }
                break;
            case ">", "<", ">=", "<=":
                if ((left.isNumeric() && left.equals(right)) || left.kind() == PrlType.Kind.STRING
                        && right.kind() == PrlType.Kind.STRING) {
                    return PrlType.BOOL;
                }
                break;
            case "in", "not in":
                checkMembership(left, right, at);
                return PrlType.BOOL;
            default:
                break;
        }
        error("运算符 '" + op + "' 不支持 " + left.display() + " 与 " + right.display(),
                at.line(), at.col());
        return PrlType.UNKNOWN;
    }

    /** {@code ==}/{@code !=}：同类型可比，{@code null} 与一切引用型可比。 */
    private static boolean isComparable(PrlType left, PrlType right) {
        if (left.equals(right)) {
            return true;
        }
        if (left.kind() == PrlType.Kind.NULL) {
            return right.isReference();
        }
        if (right.kind() == PrlType.Kind.NULL) {
            return left.isReference();
        }
        return false;
    }

    private void checkMembership(PrlType element, PrlType container, AstNode at) {
        if (container.isUnknown()) {
            return;
        }
        PrlType expected = switch (container.kind()) {
            case LIST, SET -> container.elementType();
            case MAP -> container.keyType();
            case STRING -> PrlType.STRING;
            case TUPLE -> null;
            default -> {
                error("'in' 右侧需要集合或字符串，实际为 " + container.display(), at.line(), at.col());
                yield null;
            }
        };
        if (expected == null) {
            return;
        }
        if (!PrlType.matches(expected, element)) {
            error("'in' 的元素类型不匹配：集合元素为 " + expected.display() + "，实际为 " + element.display(),
                    at.line(), at.col());
        }
    }

    private PrlType unaryOp(Expression.UnaryExpr unary) {
        PrlType operand = infer(unary.operand());
        if ("NOT".equals(unary.op())) {
            requireType(operand, PrlType.BOOL, "NOT 的操作数", unary.operand());
            return PrlType.BOOL;
        }
        if (operand.isUnknown()) {
            return PrlType.UNKNOWN;
        }
        if (!operand.isNumeric()) {
            error("一元运算符 '" + unary.op() + "' 只支持数值，实际为 " + operand.display(),
                    unary.line(), unary.col());
            return PrlType.UNKNOWN;
        }
        return operand;
    }

    // ------------------------------------------------------------------ 函数调用

    private PrlType resolveCall(Expression.CallExpr call, PrlType pipedFirst) {
        String name = call.name();
        List<Expression.Arg> args = call.args();
        int line = call.line();
        int col = call.col();
        List<HostFunction> candidates = signatures.lookup(name);
        if (candidates.isEmpty()) {
            error("未知函数 '" + name + "'（§2.11.1 L3 白名单外的调用一律拒绝）", line, col);
            for (Expression.Arg arg : args) {
                infer(arg.value());
            }
            return PrlType.UNKNOWN;
        }
        if (hostWhitelist != null && !hostWhitelist.contains(name)) {
            error("宿主未提供函数 '" + name + "'（§2.11.2 getAvailableFunctions）", line, col);
        }
        String context = "函数 " + name;

        if (candidates.size() == 1) {
            HostFunction only = candidates.get(0);
            Map<String, PrlType> bindings = new LinkedHashMap<>();
            int[] order = new int[args.size()];
            if (!bindArgs(only, context, args, pipedFirst, bindings, order)) {
                return PrlType.UNKNOWN;
            }
            recordArgOrder(call, order);
            return PrlType.substitute(only.returnType(), bindings);
        }

        int bestScore = -1;
        HostFunction best = null;
        for (HostFunction candidate : candidates) {
            Map<String, PrlType> probe = new LinkedHashMap<>();
            boolean savedSilent = silent;
            silent = true;
            boolean matched;
            try {
                matched = bindArgs(candidate, context, args, pipedFirst, probe);
            } finally {
                silent = savedSilent;
            }
            if (matched) {
                Map<String, PrlType> bindings = new LinkedHashMap<>();
                int[] order = new int[args.size()];
                bindArgs(candidate, context, args, pipedFirst, bindings, order);
                recordArgOrder(call, order);
                return PrlType.substitute(candidate.returnType(), bindings);
            }
            int candidateScore = argNameScore(candidate, args, pipedFirst);
            if (candidateScore > bestScore) {
                bestScore = candidateScore;
                best = candidate;
            }
        }

        // 没有任何候选完全匹配：挑「实参名字对得上最多」的那个正式再跑一遍，
        // 这样报出来的是「哪个实参要什么类型」，而不是一句没有定位信息的重载列表。
        Map<String, PrlType> bindings = new LinkedHashMap<>();
        bindArgs(best, context, args, pipedFirst, bindings);
        return PrlType.UNKNOWN;
    }

    /**
     * 记下具名实参的落位，供字节码编译器重排实参。
     *
     * <p>顺序本来就一致的调用不记：这是绝大多数情况，记了只是白占内存。</p>
     */
    private void recordArgOrder(Expression.CallExpr call, int[] order) {
        if (silent) {
            return;
        }
        for (int i = 0; i < order.length; i++) {
            if (order[i] != i) {
                callArgOrder.put(call, order);
                return;
            }
        }
    }

    /**
     * 只按实参名与个数估一个匹配分，用于在全部候选都失败时挑出最值得报错的那个。
     *
     * <p>不做类型推断，因此可以安全地在诊断之外调用。</p>
     */
    private static int argNameScore(HostFunction function, List<Expression.Arg> args, PrlType pipedFirst) {
        int arity = function.arity();
        boolean[] filled = new boolean[arity];
        int score = 0;
        int nextPositional = 0;
        if (pipedFirst != null && arity > 0) {
            filled[0] = true;
            nextPositional = 1;
            score = 1;
        }
        for (Expression.Arg arg : args) {
            int index;
            if (arg.name() == null) {
                if (nextPositional >= arity) {
                    continue;
                }
                index = nextPositional++;
            } else {
                index = function.indexOfParam(arg.name());
                if (index < 0) {
                    continue;
                }
                nextPositional = Math.max(nextPositional, index + 1);
            }
            if (filled[index]) {
                continue;
            }
            filled[index] = true;
            score++;
        }
        return score;
    }

    /**
     * 把实参与签名对齐并检查类型，绑定泛型占位符。
     *
     * @param pipedFirst 管道左值（§2.2.4 的 {@code |>}），非 null 时它占第 0 个形参位
     * @return 全部实参匹配且补齐返回 true
     */
    private boolean bindArgs(HostFunction function, String context, List<Expression.Arg> args,
                             PrlType pipedFirst, Map<String, PrlType> bindings) {
        return bindArgs(function, context, args, pipedFirst, bindings, null);
    }

    /**
     * 把实参逐个绑到形参上，同时校验类型。
     *
     * @param order 非空时把「第 i 个实参落到第几个形参」写进去，编译器据此重排具名实参
     */
    private boolean bindArgs(HostFunction function, String context, List<Expression.Arg> args,
                             PrlType pipedFirst, Map<String, PrlType> bindings, int[] order) {
        int arity = function.arity();
        boolean[] filled = new boolean[arity];
        int nextPositional = 0;

        if (pipedFirst != null) {
            if (arity == 0) {
                error(context + " 不接受管道左值（签名 " + function.signature() + " 没有形参）",
                        firstLine(args), firstCol(args));
                return false;
            }
            PrlType pattern = function.paramType(0);
            if (!PrlType.matches(pattern, pipedFirst, bindings)) {
                error(context + " 的管道左值需要 " + PrlType.substitute(pattern, bindings).display()
                        + "，实际为 " + pipedFirst.display(), firstLine(args), firstCol(args));
                return false;
            }
            filled[0] = true;
            nextPositional = 1;
        }

        for (int argIndex = 0; argIndex < args.size(); argIndex++) {
            Expression.Arg arg = args.get(argIndex);
            int index;
            if (arg.name() == null) {
                if (nextPositional >= arity) {
                    error(context + " 最多接受 " + arity + " 个实参，多余的是第 " + (nextPositional + 1) + " 个",
                            firstLine(args), firstCol(args));
                    return false;
                }
                index = nextPositional++;
            } else {
                index = function.indexOfParam(arg.name());
                if (index < 0) {
                    error(context + " 没有名为 '" + arg.name() + "' 的形参，可选："
                            + paramNames(function), firstLine(args), firstCol(args));
                    return false;
                }
                nextPositional = Math.max(nextPositional, index + 1);
            }
            if (filled[index]) {
                error(context + " 的形参 '" + function.params().get(index).name() + "' 被重复传入",
                        firstLine(args), firstCol(args));
                return false;
            }
            filled[index] = true;
            if (order != null) {
                order[argIndex] = index;
            }

            PrlType pattern = function.paramType(index);
            PrlType actual = inferArgument(pattern, arg.value(), bindings);
            if (!PrlType.matches(pattern, actual, bindings)) {
                error(context + " 的实参 '" + function.params().get(index).name() + "' 需要 "
                        + PrlType.substitute(pattern, bindings).display() + "，实际为 " + actual.display(),
                        arg.value().line(), arg.value().col());
                return false;
            }
        }

        for (int i = 0; i < arity; i++) {
            if (!filled[i]) {
                error(context + " 缺少实参 '" + function.params().get(i).name() + "'（"
                        + function.signatureWithNames() + "）", firstLine(args), firstCol(args));
                return false;
            }
        }
        return true;
    }

    private static String paramNames(HostFunction function) {
        StringBuilder sb = new StringBuilder();
        for (HostFunction.Param p : function.params()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(p.name());
        }
        return sb.toString();
    }

    private static int firstLine(List<Expression.Arg> args) {
        return args.isEmpty() ? 0 : args.get(0).value().line();
    }

    private static int firstCol(List<Expression.Arg> args) {
        return args.isEmpty() ? 0 : args.get(0).value().col();
    }

    /**
     * 推断一个实参的类型。
     *
     * <p>lambda 需要被调函数的签名才知道参数类型，因此这里在 lambda 与 {@code FUNC} 形参相遇时
     * 先把形参按当前绑定结果替换成具体类型，再用它作为 lambda 参数的声明类型。</p>
     */
    private PrlType inferArgument(PrlType pattern, Expression value, Map<String, PrlType> bindings) {
        if (value instanceof Expression.LambdaExpr lambda && pattern.kind() == PrlType.Kind.FUNC) {
            return inferLambda(lambda, PrlType.substitute(pattern, bindings));
        }
        return infer(value);
    }

    private PrlType inferLambda(Expression.LambdaExpr lambda, PrlType expected) {
        pushScope();
        List<PrlType> paramTypes = new ArrayList<>(lambda.params().size());
        boolean expectedShape = expected != null && expected.kind() == PrlType.Kind.FUNC;
        if (expectedShape && expected.params().size() != lambda.params().size()) {
            error("lambda 需要 " + expected.params().size() + " 个参数，实际是 " + lambda.params().size() + " 个",
                    lambda.line(), lambda.col());
        }
        for (int i = 0; i < lambda.params().size(); i++) {
            PrlType declared = expectedShape && i < expected.params().size()
                    ? sanitize(expected.params().get(i))
                    : PrlType.UNKNOWN;
            paramTypes.add(declared);
            declare(lambda.params().get(i), declared, lambda.line(), lambda.col());
        }
        PrlType bodyType = infer(lambda.body());
        popScope();
        if (expectedShape) {
            // 期望返回类型可能还是未绑定的占位符（§2.4.3 map 的 U）：先降级成未知类型做一次校验，
            // 但返回给调用方的仍是 lambda 体推出来的实际类型 —— 占位符靠外层 matches 绑定，
            // 这里把占位符塞回去会让 U 绑到它自己。
            checkAssignable(sanitize(expected.returnType()), bodyType, "lambda 的返回值", lambda.body());
        }
        return PrlType.func(paramTypes, bodyType);
    }

    private PrlType inferMethodCall(Expression.MethodCall call) {
        PrlType receiver = infer(call.receiver());
        if (receiver.isUnknown()) {
            for (Expression.Arg arg : call.args()) {
                infer(arg.value());
            }
            return PrlType.UNKNOWN;
        }
        if (receiver.kind() != PrlType.Kind.HOST) {
            error("'" + call.name() + "()' 只能在宿主对象上调用，实际接收者是 " + receiver.display(),
                    call.line(), call.col());
            for (Expression.Arg arg : call.args()) {
                infer(arg.value());
            }
            return PrlType.UNKNOWN;
        }
        HostType hostType = hostTypes.get(receiver.name());
        HostFunction method = hostType == null ? null : hostType.method(call.name());
        if (method == null) {
            error("宿主类型 " + receiver.name() + " 没有方法 '" + call.name() + "'，可用方法："
                    + (hostType == null ? "（未注册）" : hostType.methodNames()), call.line(), call.col());
            for (Expression.Arg arg : call.args()) {
                infer(arg.value());
            }
            return PrlType.UNKNOWN;
        }
        Map<String, PrlType> bindings = new LinkedHashMap<>();
        if (!bindArgs(method, receiver.name() + "." + call.name(), call.args(), null, bindings)) {
            return PrlType.UNKNOWN;
        }
        return PrlType.substitute(method.returnType(), bindings);
    }

    private PrlType inferMemberAccess(Expression.MemberAccess memberAccess) {
        PrlType target = infer(memberAccess.target());
        if (target.isUnknown()) {
            return PrlType.UNKNOWN;
        }
        if (target.kind() == PrlType.Kind.MAP) {
            error("map 的取值用下标：x[\"key\"]", memberAccess.line(), memberAccess.col());
            return PrlType.UNKNOWN;
        }
        if (target.kind() != PrlType.Kind.HOST) {
            error("类型 " + target.display() + " 没有成员 '" + memberAccess.member() + "'",
                    memberAccess.line(), memberAccess.col());
            return PrlType.UNKNOWN;
        }
        HostType hostType = hostTypes.get(target.name());
        if (hostType != null && hostType.hasField(memberAccess.member())) {
            return hostType.field(memberAccess.member());
        }
        if (hostType != null && hostType.hasMethod(memberAccess.member())) {
            HostFunction method = hostType.method(memberAccess.member());
            if (method.arity() == 0) {
                // §2.3.3 把 player.is_trusted 写成字段、§2.2.1 写成调用，两种写法等价。
                return method.returnType();
            }
            error("'" + memberAccess.member() + "' 是方法，需要实参：" + method.signatureWithNames(),
                    memberAccess.line(), memberAccess.col());
            return PrlType.UNKNOWN;
        }
        error("宿主类型 " + target.name() + " 没有成员 '" + memberAccess.member() + "'，可用字段："
                + (hostType == null ? "（未注册）" : hostType.fieldNames()) + "，可用方法："
                + (hostType == null ? "（未注册）" : hostType.methodNames()),
                memberAccess.line(), memberAccess.col());
        return PrlType.UNKNOWN;
    }

    private PrlType inferIndexAccess(Expression.IndexAccess indexAccess) {
        PrlType target = infer(indexAccess.target());
        PrlType index = infer(indexAccess.index());
        if (target.isUnknown()) {
            return PrlType.UNKNOWN;
        }
        switch (target.kind()) {
            case LIST -> {
                checkIndexType(index, PrlType.INT, indexAccess);
                return target.elementType();
            }
            case MAP -> {
                checkAssignable(target.keyType(), index, "map 的键", indexAccess.index());
                return target.valueType();
            }
            case STRING -> {
                checkIndexType(index, PrlType.INT, indexAccess);
                return PrlType.STRING;
            }
            case TUPLE -> {
                if (indexAccess.index() instanceof Expression.IntLiteral literal) {
                    int i = (int) literal.value();
                    if (i < 0 || i >= target.argCount()) {
                        error("元组下标越界：长度为 " + target.argCount() + "，下标为 " + i,
                                indexAccess.line(), indexAccess.col());
                        return PrlType.UNKNOWN;
                    }
                    return target.arg(i);
                }
                error("元组下标必须是整数字面量，才能在编译期确定元素类型",
                        indexAccess.line(), indexAccess.col());
                return PrlType.UNKNOWN;
            }
            default -> {
                error("类型 " + target.display() + " 不支持下标访问", indexAccess.line(), indexAccess.col());
                return PrlType.UNKNOWN;
            }
        }
    }

    private PrlType inferListLiteral(Expression.ListLiteral list) {
        PrlType element = null;
        for (Expression item : list.elements()) {
            PrlType itemType = infer(item);
            PrlType merged = mergeOrNull(element, itemType);
            if (merged == null) {
                error("列表元素类型不一致：" + element.display() + " 与 " + itemType.display()
                        + "（§2.3.5 不允许隐式转换）", item.line(), item.col());
                merged = PrlType.UNKNOWN;
            }
            element = merged;
        }
        return PrlType.list(element == null ? PrlType.UNKNOWN : element);
    }

    private PrlType inferSetLiteral(Expression.SetLiteral set) {
        PrlType element = null;
        for (Expression item : set.elements()) {
            PrlType itemType = infer(item);
            PrlType merged = mergeOrNull(element, itemType);
            if (merged == null) {
                error("集合元素类型不一致：" + element.display() + " 与 " + itemType.display(),
                        item.line(), item.col());
                merged = PrlType.UNKNOWN;
            }
            element = merged;
        }
        return PrlType.set(element == null ? PrlType.UNKNOWN : element);
    }

    /**
     * 映射表字面量。
     *
     * <p>值类型刻意宽松：§2.2.1 的 {@code evidence} 表里同时有 float 与 string，异构是证据表的常态，
     * 统一退化成 {@code map[K, unknown]} 而不是报错。键同理，只是实践中不会异构。</p>
     */
    private PrlType inferMapLiteral(Expression.MapLiteral map) {
        PrlType keyType = null;
        PrlType valueType = null;
        for (Expression.MapEntry entry : map.entries()) {
            PrlType key = infer(entry.key());
            PrlType value = infer(entry.value());
            keyType = mergeOrUnknown(keyType, key);
            valueType = mergeOrUnknown(valueType, value);
        }
        return PrlType.map(keyType == null ? PrlType.UNKNOWN : keyType,
                valueType == null ? PrlType.UNKNOWN : valueType);
    }

    /** 与 {@link #mergeOrNull} 相同，但不兼容时退化成未知类型而不是报错。 */
    private static PrlType mergeOrUnknown(PrlType a, PrlType b) {
        PrlType merged = mergeOrNull(a, b);
        if (merged == null) {
            return PrlType.UNKNOWN;
        }
        return merged;
    }

    private PrlType inferIfExpr(Expression.IfExpr ifExpr) {
        PrlType condition = infer(ifExpr.condition());
        requireType(condition, PrlType.BOOL, "if 表达式的条件", ifExpr.condition());
        PrlType thenType = infer(ifExpr.thenExpr());
        PrlType elseType = infer(ifExpr.elseExpr());
        if (thenType.isUnknown()) {
            return elseType;
        }
        if (elseType.isUnknown()) {
            return thenType;
        }
        PrlType merged = mergeOrNull(thenType, elseType);
        if (merged == null) {
            error("if 表达式两个分支类型不一致：" + thenType.display() + " 与 " + elseType.display(),
                    ifExpr.line(), ifExpr.col());
            return PrlType.UNKNOWN;
        }
        return merged;
    }

    /** 管道 {@code left |> f(a)} 等价于 {@code f(left, a)}（§2.2.4）。 */
    private PrlType inferPipe(Expression.PipeExpr pipe) {
        PrlType left = infer(pipe.left());
        Expression right = pipe.right();
        if (right instanceof Expression.CallExpr call) {
            return resolveCall(call, left);
        }
        if (right instanceof Expression.LambdaExpr lambda) {
            error("管道右侧需要函数调用，如 x |> filter(e -> ...)", pipe.line(), pipe.col());
            infer(lambda);
            return PrlType.UNKNOWN;
        }
        infer(right);
        error("管道右侧需要函数调用，实际为 " + right.getClass().getSimpleName(), pipe.line(), pipe.col());
        return PrlType.UNKNOWN;
    }

    // ------------------------------------------------------------------ 类型工具

    private PrlType resolveType(TypeRef ref) {
        try {
            return hostTypes.resolve(ref);
        } catch (TypeException e) {
            error(e.getMessage(), ref.line(), ref.col());
            return null;
        }
    }

    private void requireType(PrlType actual, PrlType expected, String what, AstNode at) {
        if (actual == null || actual.isUnknown() || actual.equals(expected)) {
            return;
        }
        error(what + " 需要 " + expected.display() + "，实际为 " + actual.display(), at.line(), at.col());
    }

    private void checkAssignable(PrlType expected, PrlType actual, String what, AstNode at) {
        if (expected == null || actual == null || expected.isUnknown() || actual.isUnknown()) {
            return;
        }
        if (expected.equals(actual)) {
            return;
        }
        if (actual.kind() == PrlType.Kind.NULL && expected.isReference()) {
            return;
        }
        error(what + " 类型不匹配：需要 " + expected.display() + "，实际为 " + actual.display(), at.line(), at.col());
    }

    /**
     * 合并两个「同型」类型；不兼容返回 {@code null}。
     *
     * <p>{@code int} 与 {@code float} 视作不兼容（§2.3.5 无隐式转换），混在列表字面量里会报错。</p>
     */
    private static PrlType mergeOrNull(PrlType a, PrlType b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a.equals(b)) {
            return a;
        }
        if (a.isUnknown()) {
            return b;
        }
        if (b.isUnknown()) {
            return a;
        }
        if (a.kind() == PrlType.Kind.NULL && b.isReference()) {
            return b;
        }
        if (b.kind() == PrlType.Kind.NULL && a.isReference()) {
            return a;
        }
        return null;
    }

    /** 把签名里的占位符/模式类型降级成「未知类型」，作为 lambda 形参的实际声明类型。 */
    private static PrlType sanitize(PrlType type) {
        if (type == null) {
            return PrlType.UNKNOWN;
        }
        return switch (type.kind()) {
            case VAR, NUMBER, ANY, VOID, NULL -> PrlType.UNKNOWN;
            default -> type;
        };
    }

    private void error(String message, int line, int col) {
        report(Diagnostic.Level.ERROR, "PRL-T", message, line, col);
    }

    private void warning(String message, int line, int col) {
        report(Diagnostic.Level.WARNING, "PRL-T", message, line, col);
    }

    private void report(Diagnostic.Level level, String code, String message, int line, int col) {
        if (silent) {
            return;
        }
        diagnostics.add(new Diagnostic(level, code, message, line, col));
    }
}