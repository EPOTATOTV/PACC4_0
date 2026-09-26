package com.potatotv.prl.ir;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.ast.Expression;
import com.potatotv.prl.ast.InputDecl;
import com.potatotv.prl.ast.LetDecl;
import com.potatotv.prl.ast.Rule;
import com.potatotv.prl.ast.RuleFile;
import com.potatotv.prl.ast.Statement;
import com.potatotv.prl.ast.UnitKind;
import com.potatotv.prl.check.TypeCheckResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * AST 降级为三地址码（设计文档 §2.7.1）。
 *
 * <p>做法是教科书式的「表达式求值到临时值、语句落到变量槽」：变量（{@code input} 参数与 {@code let}）
 * 都有名字，临时值只有编号，所以 IR 长得和文档 §2.7.1 的样例一样 —— {@code LOAD}/{@code STORE} 走名字，
 * 中间结果走 {@code t0}/{@code t1}。</p>
 *
 * <p>几个刻意的取舍：</p>
 * <ul>
 *   <li><b>临时值编号按语句重置。</b>一条语句里的临时值不会活到语句结束（{@code let} 已经把它落进变量槽），
 *       所以语句开头把编号计数器拨回基准值，寄存器文件就永远是「同时活跃的临时值个数」那么小。循环是例外：
 *       循环头（游标）的编号要跨迭代活着，进入循环体前把基准值抬到游标之上，循环结束再拨回来。</li>
 *   <li><b>{@code AND}/{@code OR}/{@code ??} 短路。</b>文档 §2.7.1 的样例写成 {@code t8 = AND t5, t7}，
 *       但 {@code x != null AND x.damage > 5} 这种写法在规则里很常见，不求短路就会在 {@code null} 上取成员。
 *       因此这三条走标签跳转而不是无条件求值。</li>
 *   <li><b>lambda 编成嵌套函数。</b>捕获变量在 CLOSURE 处取值、在函数里当普通槽位用，
 *       lambda 体内引用捕获变量与引用自身形参走同一条 {@code LOAD}。</li>
 *   <li><b>具名实参在这里重排。</b>谁占第几个形参只有类型检查器知道（重载解析的结果），
 *       所以顺序从 {@link TypeCheckResult#argumentOrder} 取，这里不再猜一遍。</li>
 * </ul>
 */
public final class IrGenerator {

    private IrGenerator() {
    }

    public static IrProgram generate(RuleFile file, TypeCheckResult types) {
        Builder builder = new Builder(types);
        for (Rule rule : file.rules()) {
            builder.generateRule(rule);
        }
        if (builder.functions.isEmpty()) {
            throw new PrlException("规则文件里没有规则");
        }
        return new IrProgram(builder.functions);
    }

    /** 单条规则编译，供单测与调试器使用。 */
    public static IrProgram generate(Rule rule, TypeCheckResult types) {
        Builder builder = new Builder(types);
        builder.generateRule(rule);
        return new IrProgram(builder.functions);
    }

    // ------------------------------------------------------------------ 程序级

    private static final class Builder {

        final List<IrFunction> functions = new ArrayList<>();
        final TypeCheckResult types;
        private int lambdaSeq;

        Builder(TypeCheckResult types) {
            this.types = types;
        }

        void generateRule(Rule rule) {
            List<String> params = new ArrayList<>();
            for (InputDecl.Field field : rule.input().fields()) {
                params.add(field.name());
            }
            Emitter emitter = new Emitter("rule_" + rule.name(), params, List.of(), this, true);
            emitter.emitRule(rule);
            emitter.finish();
        }

        String nextLambdaName() {
            return "lambda$" + lambdaSeq++;
        }
    }

    // ------------------------------------------------------------------ 函数级

    private static final class Emitter {

        private final String name;
        private final List<String> params;
        private final List<String> captures;
        private final Builder owner;
        private final boolean entry;

        private final List<IrInstr> code = new ArrayList<>();
        private final Deque<Map<String, String>> scopes = new ArrayDeque<>();

        private int nextTemp;
        private int tempBase;
        private int tempHigh;
        private int labelSeq;
        private int shadowSeq;

        /** 入口函数存放返回值结果的槽位名。{@code $} 不是合法的 PRL 标识符字符，不会和规则变量撞名。 */
        private static final String RESULT_SLOT = "$result";

        /** 是否已经往 {@link #RESULT_SLOT} 写过值（入口函数的返回值，见 {@link #emitStatement}）。 */
        private boolean resultStored;

        Emitter(String name, List<String> params, List<String> captures, Builder owner, boolean entry) {
            this.name = name;
            this.params = params;
            this.captures = captures;
            this.owner = owner;
            this.entry = entry;
        }

        /** 收尾：登记进程序，lambda 的嵌套函数在 {@link #exprLambda} 里就已各自登记过了。 */
        void finish() {
            owner.functions.add(new IrFunction(name, params, captures, code, Math.max(tempHigh, 1), entry));
        }

        // ---- 临时值与标签 ----

        private int newTemp() {
            int temp = nextTemp++;
            if (nextTemp > tempHigh) {
                tempHigh = nextTemp;
            }
            return temp;
        }

        private String newLabel(String prefix) {
            return prefix + "#" + labelSeq++;
        }

        private void emit(IrInstr instr) {
            code.add(instr);
        }

        private int konst(Object value) {
            int dst = newTemp();
            emit(IrInstr.constant(dst, value));
            return dst;
        }

        private void beginStatement() {
            nextTemp = tempBase;
        }

        // ---- 作用域 ----

        private void pushScope() {
            scopes.push(new LinkedHashMap<>());
        }

        private void popScope() {
            scopes.pop();
        }

        /** 声明一个变量，返回它在 IR 里的槽位名；与外层同名时加后缀区分。 */
        private String declare(String sourceName) {
            String slot = lookup(sourceName) == null ? sourceName : sourceName + "#" + (shadowSeq++);
            scopes.peek().put(sourceName, slot);
            return slot;
        }

        /** 只查找当前作用域链上的可见变量，未声明返回 null。 */
        private String lookup(String sourceName) {
            for (Map<String, String> scope : scopes) {
                String slot = scope.get(sourceName);
                if (slot != null) {
                    return slot;
                }
            }
            return null;
        }

        private String requireSlot(String sourceName, int line, int col) {
            String slot = lookup(sourceName);
            if (slot == null) {
                throw new PrlException("变量 '" + sourceName + "' 未声明", line, col);
            }
            return slot;
        }

        // ---- 规则 ----

        void emitRule(Rule rule) {
            pushScope();
            for (InputDecl.Field field : rule.input().fields()) {
                declare(field.name());
            }
            for (LetDecl let : rule.lets()) {
                emitLet(let);
            }
            String failLabel = rule.when() == null ? null : newLabel("when_false");
            if (rule.when() != null) {
                beginStatement();
                emit(IrInstr.line(rule.when().line()));
                int condition = expr(rule.when().condition());
                emit(IrInstr.jumpIf(IrOp.JUMP_IF_FALSE, condition, failLabel));
            }
            for (Statement statement : rule.then()) {
                emitStatement(statement);
            }
            if (resultStored) {
                // 从槽位重新取一遍而不是直接用写进去时的临时值：临时值编号在语句之间是复用的，
                // 后面几条语句早就把那个寄存器覆盖了（§2.12.2 的 vm.execute 拿到的必须是 DetectionResult）。
                beginStatement();
                int result = newTemp();
                emit(IrInstr.load(result, RESULT_SLOT));
                emit(IrInstr.ret(result));
            } else {
                emit(IrInstr.retVoid());
            }
            if (failLabel != null) {
                emit(IrInstr.label(failLabel));
                emit(IrInstr.retVoid());
            }
            popScope();
        }

        private void emitLet(LetDecl let) {
            // 行号标记不占寄存器，放在取临时值之前只是为了顺序上更像源码：它记的是这条 let 自己那一行。
            emit(IrInstr.line(let.line()));
            beginStatement();
            int value = expr(let.initializer());
            // let 在规则头部与语句位置都可能出现；语句位置要先声明再存，这里统一声明。
            String slot = scopes.peek().containsKey(let.name()) ? scopes.peek().get(let.name()) : declare(let.name());
            emit(IrInstr.store(slot, value));
        }

        // ---- 语句 ----

        private void emitStatement(Statement statement) {
            emit(IrInstr.line(statement.line()));
            if (statement instanceof LetDecl let) {
                emitLet(let);
                return;
            }
            beginStatement();
            if (statement instanceof Statement.IfStmt ifStmt) {
                emitIf(ifStmt);
            } else if (statement instanceof Statement.ForStmt forStmt) {
                emitFor(forStmt);
            } else if (statement instanceof Statement.WhileStmt whileStmt) {
                emitWhile(whileStmt);
            } else if (statement instanceof Statement.ReturnStmt returnStmt) {
                emit(IrInstr.ret(expr(returnStmt.value())));
            } else if (statement instanceof Statement.ExprStmt exprStmt) {
                // 动作函数（emit_alert）通常当语句用，但它的返回值就是规则的执行结果（§2.12.2 的
                // RuleManager 只收 DetectionResult）。record_evidence / trigger_redscreen 这些
                // 纯副作用动作返回 null，让它们覆盖结果槽会把 emit_alert 的返回值抹掉，
                // 所以只在值非 null 时才写进去 —— 结果是「最后一条有返回值的语句」。
                int value = expr(exprStmt.expression());
                if (entry) {
                    String skipLabel = newLabel("result_skip");
                    emit(IrInstr.jumpIf(IrOp.JUMP_IF_NULL, value, skipLabel));
                    emit(IrInstr.store(RESULT_SLOT, value));
                    resultStored = true;
                    emit(IrInstr.label(skipLabel));
                }
            } else if (statement instanceof Statement.AssignStmt assignStmt) {
                emitAssign(assignStmt);
            }
        }

        private void emitIf(Statement.IfStmt ifStmt) {
            int condition = expr(ifStmt.condition());
            String elseLabel = newLabel("else");
            String endLabel = newLabel("endif");
            emit(IrInstr.jumpIf(IrOp.JUMP_IF_FALSE, condition, elseLabel));
            pushScope();
            for (Statement inner : ifStmt.thenBody()) {
                emitStatement(inner);
            }
            popScope();
            emit(IrInstr.jump(endLabel));
            emit(IrInstr.label(elseLabel));
            pushScope();
            for (Statement inner : ifStmt.elseBody()) {
                emitStatement(inner);
            }
            popScope();
            emit(IrInstr.label(endLabel));
        }

        private void emitFor(Statement.ForStmt forStmt) {
            int source = expr(forStmt.iterable());
            int cursor = newTemp();
            emit(IrInstr.getIter(cursor, source));

            int outerBase = tempBase;
            tempBase = nextTemp;
            pushScope();
            String slot = declare(forStmt.variable());
            String loopLabel = newLabel("for_loop");
            String endLabel = newLabel("for_end");
            emit(IrInstr.label(loopLabel));
            // 循环体里每一轮都会回到这一行，行号标记放在循环头，单步时才停得住。
            emit(IrInstr.line(forStmt.line()));
            int item = newTemp();
            emit(IrInstr.iterNext(item, cursor, endLabel));
            emit(IrInstr.store(slot, item));
            for (Statement inner : forStmt.body()) {
                emitStatement(inner);
            }
            emit(IrInstr.jump(loopLabel));
            emit(IrInstr.label(endLabel));
            popScope();
            // 游标到此为止不再使用，但它的编号要留给循环体内的临时值用，所以从高水位往上接着分配。
            nextTemp = tempHigh;
            tempBase = outerBase;
        }

        private void emitWhile(Statement.WhileStmt whileStmt) {
            int outerBase = tempBase;
            String loopLabel = newLabel("while_loop");
            String endLabel = newLabel("while_end");
            emit(IrInstr.label(loopLabel));
            emit(IrInstr.line(whileStmt.line()));
            int condition = expr(whileStmt.condition());
            emit(IrInstr.jumpIf(IrOp.JUMP_IF_FALSE, condition, endLabel));
            pushScope();
            for (Statement inner : whileStmt.body()) {
                emitStatement(inner);
            }
            popScope();
            emit(IrInstr.jump(loopLabel));
            emit(IrInstr.label(endLabel));
            nextTemp = tempHigh;
            tempBase = outerBase;
        }

        private void emitAssign(Statement.AssignStmt assign) {
            Expression target = assign.target();
            if (target instanceof Expression.Identifier id) {
                String slot = requireSlot(id.name(), id.line(), id.col());
                int value = expr(assign.value());
                if (assign.op() != Statement.AssignOp.ASSIGN) {
                    int current = newTemp();
                    emit(IrInstr.load(current, slot));
                    int combined = newTemp();
                    emit(IrInstr.binary(compoundOp(assign.op()), combined, current, value));
                    value = combined;
                }
                emit(IrInstr.store(slot, value));
                return;
            }
            if (target instanceof Expression.IndexAccess index) {
                int container = expr(index.target());
                int key = expr(index.index());
                int value = expr(assign.value());
                if (assign.op() != Statement.AssignOp.ASSIGN) {
                    int current = newTemp();
                    emit(IrInstr.indexGet(current, container, key));
                    int combined = newTemp();
                    emit(IrInstr.binary(compoundOp(assign.op()), combined, current, value));
                    value = combined;
                }
                emit(IrInstr.indexSet(container, key, value));
                return;
            }
            throw new PrlException("赋值目标不能是 " + target.getClass().getSimpleName(), assign.line(), assign.col());
        }

        private static IrOp compoundOp(Statement.AssignOp op) {
            return switch (op) {
                case PLUS -> IrOp.ADD;
                case MINUS -> IrOp.SUB;
                case STAR -> IrOp.MUL;
                case SLASH -> IrOp.DIV;
                case ASSIGN -> throw new IllegalArgumentException("ASSIGN 不是复合赋值");
            };
        }

        // ---- 表达式 ----

        private int expr(Expression expression) {
            if (expression instanceof Expression.IntLiteral literal) {
                return konst(literal.value());
            }
            if (expression instanceof Expression.FloatLiteral literal) {
                return konst(literal.value());
            }
            if (expression instanceof Expression.StringLiteral literal) {
                return konst(literal.value());
            }
            if (expression instanceof Expression.BoolLiteral literal) {
                return konst(literal.value());
            }
            if (expression instanceof Expression.NullLiteral) {
                return konst(null);
            }
            if (expression instanceof Expression.SeverityLiteral literal) {
                // §2.2.2 的严重级关键字在表达式位置当字符串用（§2.12.1 的元数据读取也这么处理）。
                return konst(literal.value().name().toLowerCase(Locale.ROOT));
            }
            if (expression instanceof Expression.UnitLiteral literal) {
                return exprUnit(literal);
            }
            if (expression instanceof Expression.Identifier id) {
                int dst = newTemp();
                emit(IrInstr.load(dst, requireSlot(id.name(), id.line(), id.col())));
                return dst;
            }
            if (expression instanceof Expression.BinaryExpr binary) {
                return exprBinary(binary);
            }
            if (expression instanceof Expression.UnaryExpr unary) {
                return exprUnary(unary);
            }
            if (expression instanceof Expression.CallExpr call) {
                return exprCall(call, IrInstr.NONE);
            }
            if (expression instanceof Expression.PipeExpr pipe) {
                return exprPipe(pipe);
            }
            if (expression instanceof Expression.MethodCall methodCall) {
                int receiver = expr(methodCall.receiver());
                int dst = newTemp();
                emit(IrInstr.methodCall(dst, receiver, methodCall.name(), argTemps(methodCall.args(), null)));
                return dst;
            }
            if (expression instanceof Expression.MemberAccess member) {
                int targetTemp = expr(member.target());
                int dst = newTemp();
                emit(IrInstr.memberGet(dst, targetTemp, member.member()));
                return dst;
            }
            if (expression instanceof Expression.IndexAccess index) {
                int container = expr(index.target());
                int key = expr(index.index());
                int dst = newTemp();
                emit(IrInstr.indexGet(dst, container, key));
                return dst;
            }
            if (expression instanceof Expression.LambdaExpr lambda) {
                return exprLambda(lambda);
            }
            if (expression instanceof Expression.ListLiteral list) {
                return exprCollection(IrOp.LIST_NEW, list.elements());
            }
            if (expression instanceof Expression.SetLiteral set) {
                return exprCollection(IrOp.SET_NEW, set.elements());
            }
            if (expression instanceof Expression.TupleLiteral tuple) {
                return exprCollection(IrOp.TUPLE_NEW, tuple.elements());
            }
            if (expression instanceof Expression.MapLiteral map) {
                return exprMap(map);
            }
            if (expression instanceof Expression.RangeExpr range) {
                int start = expr(range.start());
                int end = expr(range.end());
                int dst = newTemp();
                emit(IrInstr.binary(IrOp.RANGE, dst, start, end));
                return dst;
            }
            if (expression instanceof Expression.IfExpr ifExpr) {
                return exprIf(ifExpr);
            }
            if (expression instanceof Expression.InterpolatedString interpolated) {
                return exprInterpolated(interpolated);
            }
            throw new PrlException("不支持的表达式 " + expression.getClass().getSimpleName(),
                    expression.line(), expression.col());
        }

        private int exprUnit(Expression.UnitLiteral literal) {
            return switch (literal.kind()) {
                case TIME -> konst(literal.millis());
                case DATA -> konst(literal.bytes());
                // 速率是「每秒次数」，per_minute 在编译期就折成每秒，规则侧不必关心原始单位。
                case RATE -> konst(literal.perSecond());
            };
        }

        private int exprBinary(Expression.BinaryExpr binary) {
            if ("AND".equals(binary.op())) {
                return exprShortCircuit(binary, true);
            }
            if ("OR".equals(binary.op())) {
                return exprShortCircuit(binary, false);
            }
            if ("??".equals(binary.op())) {
                return exprCoalesce(binary);
            }
            int left = expr(binary.left());
            int right = expr(binary.right());
            int dst = newTemp();
            emit(IrInstr.binary(binaryOp(binary.op(), binary), dst, left, right));
            return dst;
        }

        private static IrOp binaryOp(String symbol, Expression.BinaryExpr at) {
            return switch (symbol) {
                case "+" -> IrOp.ADD;
                case "-" -> IrOp.SUB;
                case "*" -> IrOp.MUL;
                case "/" -> IrOp.DIV;
                case "%" -> IrOp.MOD;
                case "^" -> IrOp.POW;
                case ">" -> IrOp.GT;
                case "<" -> IrOp.LT;
                case ">=" -> IrOp.GTE;
                case "<=" -> IrOp.LTE;
                case "==" -> IrOp.EQ;
                case "!=" -> IrOp.NEQ;
                case "in" -> IrOp.IN;
                case "not in" -> IrOp.NOT_IN;
                default -> throw new PrlException("未知的二元运算符 '" + symbol + "'", at.line(), at.col());
            };
        }

        /** 短路求值：{@code false AND x} 不求 {@code x}，{@code true OR x} 同理。 */
        private int exprShortCircuit(Expression.BinaryExpr binary, boolean isAnd) {
            int dst = newTemp();
            int left = expr(binary.left());
            emit(IrInstr.constant(dst, !isAnd));
            String endLabel = newLabel(isAnd ? "and_end" : "or_end");
            emit(IrInstr.jumpIf(isAnd ? IrOp.JUMP_IF_FALSE : IrOp.JUMP_IF_TRUE, left, endLabel));
            int right = expr(binary.right());
            emit(IrInstr.move(dst, right));
            emit(IrInstr.label(endLabel));
            return dst;
        }

        private int exprCoalesce(Expression.BinaryExpr binary) {
            int dst = newTemp();
            int left = expr(binary.left());
            String fallback = newLabel("coalesce");
            String endLabel = newLabel("coalesce_end");
            emit(IrInstr.jumpIf(IrOp.JUMP_IF_NULL, left, fallback));
            emit(IrInstr.move(dst, left));
            emit(IrInstr.jump(endLabel));
            emit(IrInstr.label(fallback));
            emit(IrInstr.move(dst, expr(binary.right())));
            emit(IrInstr.label(endLabel));
            return dst;
        }

        private int exprIf(Expression.IfExpr ifExpr) {
            int dst = newTemp();
            int condition = expr(ifExpr.condition());
            String elseLabel = newLabel("ifexpr_else");
            String endLabel = newLabel("ifexpr_end");
            emit(IrInstr.jumpIf(IrOp.JUMP_IF_FALSE, condition, elseLabel));
            emit(IrInstr.move(dst, expr(ifExpr.thenExpr())));
            emit(IrInstr.jump(endLabel));
            emit(IrInstr.label(elseLabel));
            emit(IrInstr.move(dst, expr(ifExpr.elseExpr())));
            emit(IrInstr.label(endLabel));
            return dst;
        }

        private int exprUnary(Expression.UnaryExpr unary) {
            if ("NOT".equals(unary.op())) {
                int dst = newTemp();
                emit(IrInstr.unary(IrOp.NOT, dst, expr(unary.operand())));
                return dst;
            }
            int operand = expr(unary.operand());
            if ("+".equals(unary.op())) {
                return operand;
            }
            int dst = newTemp();
            emit(IrInstr.unary(IrOp.NEG, dst, operand));
            return dst;
        }

        private int exprCall(Expression.CallExpr call, int pipedValue) {
            int[] order = owner.types == null ? null : owner.types.argumentOrder(call);
            int[] slots = new int[call.args().size() + (pipedValue == IrInstr.NONE ? 0 : 1)];
            if (pipedValue != IrInstr.NONE) {
                slots[0] = pipedValue;
            }
            for (int i = 0; i < call.args().size(); i++) {
                int value = expr(call.args().get(i).value());
                int position = order != null ? order[i] : (pipedValue == IrInstr.NONE ? i : i + 1);
                slots[position] = value;
            }
            int dst = newTemp();
            emit(IrInstr.call(dst, call.name(), slots));
            return dst;
        }

        private int[] argTemps(List<Expression.Arg> args, int[] order) {
            int[] slots = new int[args.size()];
            for (int i = 0; i < args.size(); i++) {
                slots[order == null ? i : order[i]] = expr(args.get(i).value());
            }
            return slots;
        }

        private int exprPipe(Expression.PipeExpr pipe) {
            Expression right = pipe.right();
            if (right instanceof Expression.CallExpr call) {
                return exprCall(call, expr(pipe.left()));
            }
            if (right instanceof Expression.LambdaExpr lambda) {
                // §2.2.4 只允许管道右侧是函数调用；lambda 形态留给类型检查器报错，这里给个兜底解释。
                int function = exprLambda(lambda);
                int dst = newTemp();
                emit(IrInstr.callValue(dst, function, new int[]{expr(pipe.left())}));
                return dst;
            }
            throw new PrlException("管道右侧需要函数调用", pipe.line(), pipe.col());
        }

        private int exprCollection(IrOp op, List<Expression> elements) {
            int[] temps = new int[elements.size()];
            for (int i = 0; i < elements.size(); i++) {
                temps[i] = expr(elements.get(i));
            }
            int dst = newTemp();
            emit(IrInstr.collection(op, dst, temps));
            return dst;
        }

        private int exprMap(Expression.MapLiteral map) {
            int[] pairs = new int[map.entries().size() * 2];
            for (int i = 0; i < map.entries().size(); i++) {
                Expression.MapEntry entry = map.entries().get(i);
                pairs[i * 2] = expr(entry.key());
                pairs[i * 2 + 1] = expr(entry.value());
            }
            int dst = newTemp();
            emit(IrInstr.collection(IrOp.MAP_NEW, dst, pairs));
            return dst;
        }

        private int exprInterpolated(Expression.InterpolatedString interpolated) {
            int[] parts = new int[interpolated.parts().size()];
            for (int i = 0; i < interpolated.parts().size(); i++) {
                Expression part = interpolated.parts().get(i);
                if (part instanceof Expression.StringLiteral literal) {
                    parts[i] = konst(literal.value());
                } else {
                    int value = expr(part);
                    int text = newTemp();
                    emit(IrInstr.unary(IrOp.TO_STRING, text, value));
                    parts[i] = text;
                }
            }
            int dst = newTemp();
            emit(IrInstr.stringInterp(dst, parts));
            return dst;
        }

        /**
         * lambda：先收集自由变量，再建一个嵌套函数，最后在当前位置把捕获值装进闭包。
         *
         * <p>嵌套函数用同一个 {@link Emitter} 机制，只是作用域栈的底是形参 + 捕获名；
         * 这样 lambda 体里的变量引用不需要任何特殊指令。</p>
         */
        private int exprLambda(Expression.LambdaExpr lambda) {
            Set<String> bound = new LinkedHashSet<>(lambda.params());
            Set<String> free = new LinkedHashSet<>();
            collectFree(lambda.body(), bound, free);

            // 自由变量按源码名查槽位；查不到说明是外层也不认识的引用（类型检查器已报错），跳过。
            List<String> captureNames = new ArrayList<>();
            List<String> captureSlots = new ArrayList<>();
            for (String name : free) {
                String slot = lookup(name);
                if (slot != null) {
                    captureNames.add(name);
                    captureSlots.add(slot);
                }
            }

            Emitter nested = new Emitter(owner.nextLambdaName(), lambda.params(), captureSlots, owner, false);
            nested.pushScope();
            for (String param : lambda.params()) {
                nested.scopes.peek().put(param, param);
            }
            for (int i = 0; i < captureNames.size(); i++) {
                nested.scopes.peek().put(captureNames.get(i), captureSlots.get(i));
            }
            nested.beginStatement();
            int result = nested.expr(lambda.body());
            nested.emit(IrInstr.ret(result));
            nested.popScope();
            nested.finish();

            int[] capturedValues = new int[captureSlots.size()];
            for (int i = 0; i < captureSlots.size(); i++) {
                capturedValues[i] = newTemp();
                emit(IrInstr.load(capturedValues[i], captureSlots.get(i)));
            }
            int dst = newTemp();
            emit(IrInstr.closure(dst, nested.name, capturedValues));
            return dst;
        }

        private static void collectFree(Expression expression, Set<String> bound, Set<String> out) {
            if (expression instanceof Expression.Identifier id) {
                if (!bound.contains(id.name())) {
                    out.add(id.name());
                }
                return;
            }
            if (expression instanceof Expression.LambdaExpr nestedLambda) {
                Set<String> innerBound = new LinkedHashSet<>(bound);
                innerBound.addAll(nestedLambda.params());
                collectFree(nestedLambda.body(), innerBound, out);
                return;
            }
            for (Expression child : children(expression)) {
                collectFree(child, bound, out);
            }
        }

        private static List<Expression> children(Expression expression) {
            if (expression instanceof Expression.BinaryExpr binary) {
                return List.of(binary.left(), binary.right());
            }
            if (expression instanceof Expression.UnaryExpr unary) {
                return List.of(unary.operand());
            }
            if (expression instanceof Expression.CallExpr call) {
                List<Expression> values = new ArrayList<>(call.args().size());
                for (Expression.Arg arg : call.args()) {
                    values.add(arg.value());
                }
                return values;
            }
            if (expression instanceof Expression.MethodCall methodCall) {
                List<Expression> values = new ArrayList<>();
                values.add(methodCall.receiver());
                for (Expression.Arg arg : methodCall.args()) {
                    values.add(arg.value());
                }
                return values;
            }
            if (expression instanceof Expression.MemberAccess member) {
                return List.of(member.target());
            }
            if (expression instanceof Expression.IndexAccess index) {
                return List.of(index.target(), index.index());
            }
            if (expression instanceof Expression.ListLiteral list) {
                return list.elements();
            }
            if (expression instanceof Expression.SetLiteral set) {
                return set.elements();
            }
            if (expression instanceof Expression.TupleLiteral tuple) {
                return tuple.elements();
            }
            if (expression instanceof Expression.MapLiteral map) {
                List<Expression> values = new ArrayList<>();
                for (Expression.MapEntry entry : map.entries()) {
                    values.add(entry.key());
                    values.add(entry.value());
                }
                return values;
            }
            if (expression instanceof Expression.RangeExpr range) {
                return List.of(range.start(), range.end());
            }
            if (expression instanceof Expression.PipeExpr pipe) {
                return List.of(pipe.left(), pipe.right());
            }
            if (expression instanceof Expression.IfExpr ifExpr) {
                return List.of(ifExpr.condition(), ifExpr.thenExpr(), ifExpr.elseExpr());
            }
            if (expression instanceof Expression.InterpolatedString interpolated) {
                return interpolated.parts();
            }
            return List.of();
        }
    }
}