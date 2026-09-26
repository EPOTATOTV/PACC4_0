package com.potatotv.prl.vm;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.bytecode.BytecodeFunction;
import com.potatotv.prl.bytecode.Opcode;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.bytecode.RuleEntry;
import com.potatotv.prl.runtime.PrlCallable;
import com.potatotv.prl.runtime.PrlCursor;
import com.potatotv.prl.runtime.PrlExecutionException;
import com.potatotv.prl.runtime.PrlExecutionObserver;
import com.potatotv.prl.runtime.PrlFrameView;
import com.potatotv.prl.runtime.PrlSecurityException;
import com.potatotv.prl.runtime.PrlTimeoutException;
import com.potatotv.prl.runtime.PrlValues;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.stdlib.PrlStdlib;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * PRL 字节码解释器（设计文档 §2.9.1、§2.11.1 L2）。
 *
 * <p>文档里的样例把 {@code stack} 当操作数栈用，这里把它当<strong>寄存器文件</strong>用：
 * 字节码的寄存器编号直接下标进去，不需要 {@code sp}。原因是 IR 生成阶段已经算好了寄存器编号
 * （见 {@code IrGenerator} 的「临时值编号按语句重置」），再压栈弹栈只是把同一份信息重新编码一遍。</p>
 *
 * <p>每次 {@code execute*} 都新建一份 {@link Execution}：寄存器文件、槽位表、指令计数与超时预算
 * 都在里面，所以同一份 {@link PrlBytecode} 可以被多个线程同时执行，VM 实例本身无可变状态。
 * 嵌套调用（宿主函数回调 lambda、lambda 套 lambda）共用同一个 {@code Execution}，指令数与
 * 100ms 超时按「一次规则执行」累计，而不是按函数调用各算一遍 —— 否则递归里的死循环能绕开上限。</p>
 *
 * <p>CALL 的目标先在本程序的函数表里找，找不到才落到宿主白名单与标准库（§2.11.1 L3）。
 * 顺序不能反过来：宿主若注册了同名函数，规则内的局部函数应当优先，否则规则自己定义的辅助函数
 * 会被宿主悄悄顶掉。</p>
 */
public final class PrlVm {

    /** §2.11.1 L2：单条规则 100000 条指令。 */
    public static final int MAX_INSTRUCTIONS = 100_000;

    /** §2.11.1 L2：单条规则 100ms。 */
    public static final long TIMEOUT_NANOS = 100_000_000L;

    /** §2.11.1 L2：调用栈 64 层。 */
    public static final int MAX_CALL_DEPTH = 64;

    /** §2.11.1 L2：集合元素数上限，VM 侧只对 {@code a..b} 这类能一次撑爆内存的构造做检查。 */
    public static final int MAX_COLLECTION_SIZE = 10_000;

    /** 超时检查的间隔（条）：每 1024 条查一次 {@code System.nanoTime()}，避免把时钟读成热点。 */
    private static final int TIMEOUT_CHECK_INTERVAL = 1024;

    private static final Object[] NO_ARGS = new Object[0];

    private final PrlHostContext host;
    private final PrlExecutionObserver observer;

    public PrlVm() {
        this(PrlHostContext.EMPTY);
    }

    public PrlVm(PrlHostContext host) {
        this(host, PrlExecutionObserver.NONE);
    }

    /**
     * @param observer 调试器或 Profiler（§2.15）。传 {@link PrlExecutionObserver#NONE} 时热路径上
     *                 只多一次引用比较，指令级回调与函数耗时统计都不会发生
     */
    public PrlVm(PrlHostContext host, PrlExecutionObserver observer) {
        this.host = host == null ? PrlHostContext.EMPTY : host;
        this.observer = observer == null ? PrlExecutionObserver.NONE : observer;
    }

    // ------------------------------------------------------------------ 入口

    /** 执行程序的入口函数（§2.12.2 的 {@code vm.execute}）。{@code input} 按 {@code input} 块里的字段名取。 */
    public Object execute(PrlBytecode program, Map<String, Object> input) {
        BytecodeFunction entry = findEntry(program);
        return dispatch(program, entry, input, entry.name(), entry.name());
    }

    /** 按规则名执行。 */
    public Object executeRule(PrlBytecode program, String ruleName, Map<String, Object> input) {
        RuleEntry rule = program.rule(ruleName);
        if (rule == null) {
            throw new PrlExecutionException("字节码里没有规则 '" + ruleName + "'");
        }
        return executeRule(program, rule, input);
    }

    public Object executeRule(PrlBytecode program, RuleEntry rule, Map<String, Object> input) {
        BytecodeFunction function = program.function(rule.functionIndex());
        return dispatch(program, function, input, rule.name(), rule.name());
    }

    private static BytecodeFunction findEntry(PrlBytecode program) {
        for (BytecodeFunction function : program.functions()) {
            if (function.entry()) {
                return function;
            }
        }
        throw new PrlExecutionException("字节码里没有入口函数");
    }

    private Object dispatch(PrlBytecode program, BytecodeFunction function,
                            Map<String, Object> input, String seedSource, String ruleName) {
        Object[] args = new Object[function.params().size()];
        for (int i = 0; i < args.length; i++) {
            args[i] = input == null ? null : input.get(function.params().get(i));
        }
        // random 必须可复现（§2.4.5），种子从规则名派生，所以同一条规则在同样的输入上跑出同样的结果。
        Execution execution = new Execution(program, host, observer, seedSource.hashCode());
        if (observer == PrlExecutionObserver.NONE) {
            return invokeSafely(execution, function, args);
        }
        // Profiler 按规则归集（§2.15.2）：一次规则执行的墙钟时间在这里量，函数级耗时在调用点量。
        long start = System.nanoTime();
        observer.onRuleStart(ruleName);
        try {
            return invokeSafely(execution, function, args);
        } finally {
            observer.onRuleEnd(ruleName, System.nanoTime() - start);
        }
    }

    private static Object invokeSafely(Execution execution, BytecodeFunction function, Object[] args) {
        try {
            return invoke(execution, function, args, NO_ARGS, 1);
        } catch (PrlException e) {
            throw e;
        } catch (RuntimeException e) {
            // §2.11.1 L2 的「异常隔离」：宿主进程不能因为规则里的一个 NPE/越界而炸掉。
            throw new PrlExecutionException("规则执行抛出未预期异常：" + e, e);
        }
    }

    // ------------------------------------------------------------------ 执行状态

    /** 一次规则执行的共享状态。指令计数、超时起点、调用深度在这里，嵌套调用共享同一份。 */
    private static final class Execution {

        final PrlBytecode program;
        final PrlHostContext host;
        final PrlExecutionObserver observer;
        final Set<String> hostFunctions;
        final PrlStdlib stdlib;
        final long startNanos = System.nanoTime();

        int instructionCount;
        int currentDepth;

        /** 当前帧，用来给新建的帧记调用者；闭包回调时没有帧参数可传，只能从这里取。 */
        Frame currentFrame;

        Execution(PrlBytecode program, PrlHostContext host, PrlExecutionObserver observer, long seed) {
            this.program = program;
            this.host = host;
            this.observer = observer;
            this.hostFunctions = host.getAvailableFunctions();
            this.stdlib = new PrlStdlib(host, seed);
        }
    }

    /** 一个调用帧。寄存器文件按帧新建 —— 这是「同一份字节码可并发执行」的前提。 */
    private static final class Frame implements PrlFrameView {

        final Execution owner;
        final byte[] code;
        final Object[] registers;
        final Map<String, Object> slots = new HashMap<>();
        final String functionName;

        /** 调用者；最外层为 null。调试器要拿它串出调用栈（§2.15.1），VM 自己不用。 */
        final Frame caller;

        int ip;
        int line;
        final int depth;

        Frame(Execution owner, BytecodeFunction function, int depth, Frame caller) {
            this.owner = owner;
            this.code = function.code();
            this.registers = new Object[Math.max(function.registerCount(), 1)];
            this.functionName = function.name();
            this.depth = depth;
            this.caller = caller;
        }

        Object reg(int index) {
            return registers[index];
        }

        void setReg(int index, Object value) {
            registers[index] = value;
        }

        /** 字符串表取值。越界说明字节码被改坏了，直接报错而不是让后面 NPE。 */
        String constant(int index) {
            String text = owner.program.string(index);
            if (text == null) {
                throw new PrlExecutionException("字节码里引用了不存在的字符串表下标 " + index);
            }
            return text;
        }

        // ---- PrlFrameView：调试器读的是活数据，复制只发生在真正要看的时刻 ----

        @Override
        public String functionName() {
            return functionName;
        }

        @Override
        public int line() {
            return line;
        }

        @Override
        public int depth() {
            return depth;
        }

        @Override
        public Map<String, Object> variables() {
            return new LinkedHashMap<>(slots);
        }

        @Override
        public List<String> callStack() {
            List<String> stack = new ArrayList<>(depth);
            for (Frame frame = this; frame != null; frame = frame.caller) {
                stack.add(frame.functionName);
            }
            return stack;
        }
    }

    // ------------------------------------------------------------------ 调用

    private static Object invoke(Execution execution, BytecodeFunction function,
                                 Object[] args, Object[] captured, int depth) {
        if (depth > MAX_CALL_DEPTH) {
            throw new PrlExecutionException("调用层数超过 " + MAX_CALL_DEPTH + " 层（§2.11.1 L2）");
        }
        if (args.length != function.params().size()) {
            throw new PrlExecutionException("函数 " + function.name() + " 需要 "
                    + function.params().size() + " 个实参，实际给了 " + args.length + " 个");
        }
        Frame frame = new Frame(execution, function, depth, execution.currentFrame);
        for (int i = 0; i < args.length; i++) {
            frame.slots.put(function.params().get(i), args[i]);
        }
        for (int i = 0; i < captured.length; i++) {
            frame.slots.put(function.captures().get(i), captured[i]);
        }
        int previousDepth = execution.currentDepth;
        Frame previousFrame = execution.currentFrame;
        execution.currentDepth = depth;
        execution.currentFrame = frame;
        try {
            return loop(frame);
        } finally {
            execution.currentDepth = previousDepth;
            execution.currentFrame = previousFrame;
        }
    }

    /** §2.11.1 L3：函数名的解析顺序为 本程序函数表 → 宿主白名单 → 标准库。 */
    private static Object callByName(Execution execution, String name, Object[] args, int depth) {
        return observed(execution, name, () -> {
            int index = execution.program.indexOf(name);
            if (index >= 0) {
                return invoke(execution, execution.program.function(index), args, NO_ARGS, depth + 1);
            }
            if (execution.hostFunctions.contains(name)) {
                return execution.host.callFunction(name, args);
            }
            if (PrlStdlib.supports(name)) {
                return execution.stdlib.call(name, args);
            }
            throw new PrlSecurityException("函数 '" + name + "' 不在宿主白名单与标准库内（§2.11.1 L3）");
        });
    }

    /**
     * 把一个调用包进观察者回调里（§2.15.2 Profiler 要按函数统计耗时）。
     *
     * <p>没挂观察者时直接执行 lambda，不读时钟 —— {@code System.nanoTime()} 两次一条调用，
     * 规则里一秒钟几万次调用全花在这上面。</p>
     */
    private static Object observed(Execution execution, String name, Supplier<Object> body) {
        if (execution.observer == PrlExecutionObserver.NONE) {
            return body.get();
        }
        execution.observer.onEnterFunction(name);
        long start = System.nanoTime();
        try {
            return body.get();
        } finally {
            execution.observer.onExitFunction(name, System.nanoTime() - start);
        }
    }

    /**
     * 捕获了自由变量的 lambda（{@code Opcode.CLOSURE} 的产物）。
     *
     * <p>捕获值在创建处求值后随闭包一起走，lambda 体内引用它们与引用形参没有区别
     * （IR 里两者都是同一批槽位名）。调用深度取调用点当时的深度，这样递归也能被深度闸拦住。</p>
     */
    private static final class Closure implements PrlCallable {

        private final Execution owner;
        private final BytecodeFunction function;
        private final Object[] captured;

        Closure(Execution owner, BytecodeFunction function, Object[] captured) {
            this.owner = owner;
            this.function = function;
            this.captured = captured;
        }

        @Override
        public Object call(Object[] args) {
            return invoke(owner, function, args == null ? NO_ARGS : args, captured, owner.currentDepth + 1);
        }

        @Override
        public int arity() {
            return function.params().size();
        }

        @Override
        public String functionName() {
            return function.name();
        }
    }

    // ------------------------------------------------------------------ 主循环

    private static Object loop(Frame frame) {
        Execution execution = frame.owner;
        byte[] code = frame.code;
        while (true) {
            if (frame.ip >= code.length) {
                return null;
            }
            if (++execution.instructionCount > MAX_INSTRUCTIONS) {
                throw new PrlExecutionException("指令数超过 " + MAX_INSTRUCTIONS
                        + " 条（§2.11.1 L2，疑似死循环）");
            }
            if (execution.instructionCount % TIMEOUT_CHECK_INTERVAL == 0
                    && System.nanoTime() - execution.startNanos > TIMEOUT_NANOS) {
                throw new PrlTimeoutException("规则执行超过 " + (TIMEOUT_NANOS / 1_000_000)
                        + "ms（§2.11.1 L2）");
            }
            int opcode = code[frame.ip++] & 0xFF;
            switch (opcode) {
                // ---- 常量与搬运 ----
                case Opcode.CONST -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, frame.constant(nextShort(frame)));
                }
                case Opcode.CONST_INT -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, (long) nextInt(frame));
                }
                case Opcode.CONST_FLOAT -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, nextDouble(frame));
                }
                case Opcode.CONST_TRUE -> frame.setReg(nextByte(frame), Boolean.TRUE);
                case Opcode.CONST_FALSE -> frame.setReg(nextByte(frame), Boolean.FALSE);
                case Opcode.CONST_NULL -> frame.setReg(nextByte(frame), null);
                case Opcode.LOAD -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, frame.slots.get(frame.constant(nextShort(frame))));
                }
                case Opcode.STORE -> {
                    String slot = frame.constant(nextShort(frame));
                    frame.slots.put(slot, frame.reg(nextByte(frame)));
                }
                case Opcode.MOVE -> frame.setReg(nextByte(frame), frame.reg(nextByte(frame)));

                // ---- 算术 ----
                case Opcode.ADD -> binary(frame, PrlVm::add);
                case Opcode.SUB -> binary(frame, PrlVm::subtract);
                case Opcode.MUL -> binary(frame, PrlVm::multiply);
                case Opcode.DIV -> binary(frame, PrlVm::divide);
                case Opcode.MOD -> binary(frame, PrlVm::modulo);
                case Opcode.POW -> binary(frame, PrlVm::power);
                case Opcode.NEG -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, negate(frame.reg(nextByte(frame))));
                }
                case Opcode.NOT -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, !PrlValues.asBool(frame.reg(nextByte(frame))));
                }
                case Opcode.TO_STRING -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, PrlValues.display(frame.reg(nextByte(frame))));
                }

                // ---- 比较与逻辑 ----
                case Opcode.GT -> binary(frame, (a, b) -> PrlValues.compare(a, b) > 0);
                case Opcode.LT -> binary(frame, (a, b) -> PrlValues.compare(a, b) < 0);
                case Opcode.GTE -> binary(frame, (a, b) -> PrlValues.compare(a, b) >= 0);
                case Opcode.LTE -> binary(frame, (a, b) -> PrlValues.compare(a, b) <= 0);
                case Opcode.EQ -> binary(frame, PrlValues::equalsValue);
                case Opcode.NEQ -> binary(frame, (a, b) -> !PrlValues.equalsValue(a, b));
                case Opcode.IN -> binary(frame, (element, container) -> contains(container, element));
                case Opcode.NOT_IN -> binary(frame, (element, container) -> !contains(container, element));
                // AND/OR 只在 IR 层存在（§2.7.2），源码里的 AND/OR 一律被编译成短路跳转，
                // 所以这两条字节码是给「IR 优化器把短路改成无条件求值」留的完整映射。
                case Opcode.AND -> binary(frame, (a, b) -> PrlValues.asBool(a) && PrlValues.asBool(b));
                case Opcode.OR -> binary(frame, (a, b) -> PrlValues.asBool(a) || PrlValues.asBool(b));

                // ---- 行号与跳转 ----
                // LINE 是空操作，只更新当前行号；调试器在它上面下断点（§2.15.1）。
                case Opcode.LINE -> {
                    frame.line = nextInt(frame);
                    // 回调放在行号更新之后：调试器看到的「当前行」必须是即将开始求值的那一行。
                    // 若放在更新之前，断点会晚一条语句命中（那时这一行的副作用已经发生完了）。
                    if (frame.owner.observer != PrlExecutionObserver.NONE) {
                        frame.owner.observer.onInstruction(frame);
                    }
                }
                // 注意别写成 frame.ip += nextInt(frame)：复合赋值会先把旧的 ip 读出来，
                // 而 nextInt 内部又推进了 ip，最后拿陈旧的 ip 去加偏移，落点会少 4 字节。
                case Opcode.JUMP -> {
                    int offset = nextInt(frame);
                    frame.ip += offset;
                }
                case Opcode.JUMP_IF_FALSE -> {
                    int condition = nextByte(frame);
                    int offset = nextInt(frame);
                    if (!PrlValues.asBool(frame.reg(condition))) {
                        frame.ip += offset;
                    }
                }
                case Opcode.JUMP_IF_TRUE -> {
                    int condition = nextByte(frame);
                    int offset = nextInt(frame);
                    if (PrlValues.asBool(frame.reg(condition))) {
                        frame.ip += offset;
                    }
                }
                case Opcode.JUMP_IF_NULL -> {
                    int value = nextByte(frame);
                    int offset = nextInt(frame);
                    if (frame.reg(value) == null) {
                        frame.ip += offset;
                    }
                }

                // ---- 调用 ----
                case Opcode.CALL -> {
                    String name = frame.constant(nextShort(frame));
                    Object[] args = readArgs(frame);
                    int dst = nextByte(frame);
                    frame.setReg(dst, callByName(execution, name, args, frame.depth));
                }
                case Opcode.CALL_VALUE -> {
                    Object callee = frame.reg(nextByte(frame));
                    Object[] args = readArgs(frame);
                    int dst = nextByte(frame);
                    if (!(callee instanceof PrlCallable callable)) {
                        throw new PrlExecutionException("调用目标不是函数值："
                                + PrlValues.describe(callee));
                    }
                    if (callable.arity() != args.length) {
                        throw new PrlExecutionException("函数值需要 " + callable.arity()
                                + " 个实参，实际给了 " + args.length + " 个");
                    }
                    frame.setReg(dst, observed(execution, callable.functionName(),
                            () -> callable.call(args)));
                }
                case Opcode.METHOD_CALL -> {
                    Object receiver = frame.reg(nextByte(frame));
                    String method = frame.constant(nextShort(frame));
                    Object[] args = readArgs(frame);
                    int dst = nextByte(frame);
                    frame.setReg(dst, observed(execution, method.toLowerCase(Locale.ROOT),
                            () -> execution.host.callMethod(receiver, method, args)));
                }
                case Opcode.MEMBER_GET -> {
                    int dst = nextByte(frame);
                    Object target = frame.reg(nextByte(frame));
                    String member = frame.constant(nextShort(frame));
                    frame.setReg(dst, execution.host.getMember(target, member));
                }

                // ---- 集合 ----
                case Opcode.LIST_NEW -> frame.setReg(nextByte(frame), readElements(frame, new ArrayList<>()));
                case Opcode.TUPLE_NEW -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, Collections.unmodifiableList(readElements(frame, new ArrayList<>())));
                }
                case Opcode.SET_NEW -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, readElements(frame, new LinkedHashSet<>()));
                }
                case Opcode.MAP_NEW -> {
                    int dst = nextByte(frame);
                    int pairs = nextByte(frame);
                    Map<Object, Object> map = new LinkedHashMap<>();
                    for (int i = 0; i < pairs; i++) {
                        Object key = frame.reg(nextByte(frame));
                        map.put(key, frame.reg(nextByte(frame)));
                    }
                    frame.setReg(dst, map);
                }
                case Opcode.INDEX_GET -> {
                    int dst = nextByte(frame);
                    Object container = frame.reg(nextByte(frame));
                    Object key = frame.reg(nextByte(frame));
                    frame.setReg(dst, indexGet(container, key));
                }
                case Opcode.INDEX_SET -> {
                    Object container = frame.reg(nextByte(frame));
                    Object key = frame.reg(nextByte(frame));
                    Object value = frame.reg(nextByte(frame));
                    indexSet(container, key, value);
                }
                case Opcode.RANGE -> {
                    int dst = nextByte(frame);
                    long start = PrlValues.asLong(frame.reg(nextByte(frame)));
                    long end = PrlValues.asLong(frame.reg(nextByte(frame)));
                    frame.setReg(dst, range(start, end));
                }
                case Opcode.GET_ITER -> {
                    int dst = nextByte(frame);
                    frame.setReg(dst, PrlCursor.of(frame.reg(nextByte(frame))));
                }
                case Opcode.ITER_NEXT -> {
                    int dst = nextByte(frame);
                    Object cursor = frame.reg(nextByte(frame));
                    int offset = nextInt(frame);
                    if (cursor instanceof PrlCursor sequence && sequence.hasNext()) {
                        frame.setReg(dst, sequence.next());
                    } else {
                        frame.ip += offset;
                    }
                }
                case Opcode.STRING_INTERP -> {
                    int dst = nextByte(frame);
                    int count = nextByte(frame);
                    StringBuilder text = new StringBuilder();
                    for (int i = 0; i < count; i++) {
                        text.append(PrlValues.display(frame.reg(nextByte(frame))));
                    }
                    frame.setReg(dst, text.toString());
                }

                // ---- 函数值 ----
                case Opcode.CLOSURE -> {
                    int dst = nextByte(frame);
                    String name = frame.constant(nextShort(frame));
                    int index = execution.program.indexOf(name);
                    if (index < 0) {
                        throw new PrlExecutionException("闭包目标函数 '" + name + "' 不在字节码的函数表里");
                    }
                    Object[] captured = readArgs(frame);
                    frame.setReg(dst, new Closure(execution, execution.program.function(index), captured));
                }

                // ---- 收尾 ----
                case Opcode.RETURN -> {
                    Object value = frame.reg(nextByte(frame));
                    return value;
                }
                case Opcode.RETURN_VOID -> {
                    return null;
                }
                default -> throw new PrlExecutionException("未知字节码指令 0x"
                        + Integer.toHexString(opcode).toUpperCase(Locale.ROOT));
            }
        }
    }

    // ------------------------------------------------------------------ 操作数读取

    private static int nextByte(Frame frame) {
        return frame.code[frame.ip++] & 0xFF;
    }

    private static int nextShort(Frame frame) {
        return (nextByte(frame) << 8) | nextByte(frame);
    }

    private static int nextInt(Frame frame) {
        return (nextByte(frame) << 24) | (nextByte(frame) << 16)
                | (nextByte(frame) << 8) | nextByte(frame);
    }

    private static double nextDouble(Frame frame) {
        long bits = 0;
        for (int i = 0; i < 8; i++) {
            bits = (bits << 8) | nextByte(frame);
        }
        return Double.longBitsToDouble(bits);
    }

    private static Object[] readArgs(Frame frame) {
        int count = nextByte(frame);
        Object[] values = new Object[count];
        for (int i = 0; i < count; i++) {
            values[i] = frame.reg(nextByte(frame));
        }
        return values;
    }

    private interface BinaryOperator {
        Object apply(Object left, Object right);
    }

    /** {@code dst, a, b} 三段编码的通用形态。 */
    private static void binary(Frame frame, BinaryOperator operator) {
        int dst = nextByte(frame);
        Object left = frame.reg(nextByte(frame));
        Object right = frame.reg(nextByte(frame));
        frame.setReg(dst, operator.apply(left, right));
    }

    private static <C extends java.util.Collection<Object>> C readElements(Frame frame, C target) {
        int count = nextByte(frame);
        for (int i = 0; i < count; i++) {
            target.add(frame.reg(nextByte(frame)));
        }
        return target;
    }

    // ------------------------------------------------------------------ 运算语义

    private static boolean isFloatValue(Object value) {
        return value instanceof Double || value instanceof Float;
    }

    private static Object negate(Object value) {
        return PrlValues.isInteger(value)
                ? Long.valueOf(-PrlValues.asLong(value))
                : Double.valueOf(-PrlValues.asDouble(value));
    }

    private static Object add(Object left, Object right) {
        if (left instanceof String a && right instanceof String b) {
            return a + b;
        }
        if (left instanceof List<?> a && right instanceof List<?> b) {
            List<Object> merged = new ArrayList<>(a);
            merged.addAll(b);
            return merged;
        }
        if (isFloatValue(left) || isFloatValue(right)) {
            return PrlValues.asDouble(left) + PrlValues.asDouble(right);
        }
        return PrlValues.asLong(left) + PrlValues.asLong(right);
    }

    private static Object subtract(Object left, Object right) {
        if (isFloatValue(left) || isFloatValue(right)) {
            return PrlValues.asDouble(left) - PrlValues.asDouble(right);
        }
        return PrlValues.asLong(left) - PrlValues.asLong(right);
    }

    private static Object multiply(Object left, Object right) {
        if (isFloatValue(left) || isFloatValue(right)) {
            return PrlValues.asDouble(left) * PrlValues.asDouble(right);
        }
        return PrlValues.asLong(left) * PrlValues.asLong(right);
    }

    /**
     * 除法。{@code int / int} 是整数除法（向零截断），要小数得写 {@code 7.0 / 2.0} ——
     * §2.3.5 禁止隐式 int↔float 转换，所以两种除法在这里是明确分开的两条路，不会因为
     * 「结果看起来应该是小数」就悄悄变成浮点。
     */
    private static Object divide(Object left, Object right) {
        if (isFloatValue(left) || isFloatValue(right)) {
            double divisor = PrlValues.asDouble(right);
            if (divisor == 0.0) {
                throw new PrlExecutionException("浮点数除以零");
            }
            return PrlValues.asDouble(left) / divisor;
        }
        long divisor = PrlValues.asLong(right);
        if (divisor == 0L) {
            throw new PrlExecutionException("整数除以零");
        }
        return PrlValues.asLong(left) / divisor;
    }

    private static Object modulo(Object left, Object right) {
        if (isFloatValue(left) || isFloatValue(right)) {
            double divisor = PrlValues.asDouble(right);
            if (divisor == 0.0) {
                throw new PrlExecutionException("浮点数取模时除数为零");
            }
            return PrlValues.asDouble(left) % divisor;
        }
        long divisor = PrlValues.asLong(right);
        if (divisor == 0L) {
            throw new PrlExecutionException("整数取模时除数为零");
        }
        return PrlValues.asLong(left) % divisor;
    }

    /** 幂：两侧都是 int 且指数非负时结果仍是 int，其余走浮点（负指数没有整数解）。 */
    private static Object power(Object left, Object right) {
        double result = Math.pow(PrlValues.asDouble(left), PrlValues.asDouble(right));
        boolean integral = PrlValues.isInteger(left) && PrlValues.isInteger(right)
                && PrlValues.asLong(right) >= 0L;
        if (integral && result >= Long.MIN_VALUE && result <= Long.MAX_VALUE
                && result == Math.rint(result)) {
            return (long) result;
        }
        return result;
    }

    /** {@code x in container}：list 逐个比、set/map 按键、string 按子串。 */
    private static boolean contains(Object container, Object element) {
        if (container instanceof String text) {
            return text.contains(PrlValues.asString(element));
        }
        if (container instanceof Map<?, ?> map) {
            return map.containsKey(element);
        }
        if (container instanceof Set<?> set) {
            return set.contains(element);
        }
        if (container instanceof List<?> list) {
            for (Object item : list) {
                if (PrlValues.equalsValue(item, element)) {
                    return true;
                }
            }
            return false;
        }
        throw new PrlExecutionException("'in' 右侧需要 list/set/map/string，实际为 "
                + PrlValues.describe(container));
    }

    // ------------------------------------------------------------------ 集合语义

    private static Object indexGet(Object container, Object key) {
        if (container instanceof List<?> list) {
            int index = indexOf(key, list.size());
            return list.get(index);
        }
        if (container instanceof String text) {
            return String.valueOf(text.charAt(indexOf(key, text.length())));
        }
        if (container instanceof Map<?, ?> map) {
            // 键不存在返回 null 而不是报错：§2.3.5 要求可能为 null 的值显式处理，
            // 所以「查不到」是规则侧用 ?? 或 if != null 自己要处理的情况，不是错误。
            return map.get(key);
        }
        throw new PrlExecutionException("下标访问需要 list/string/map，实际为 "
                + PrlValues.describe(container));
    }

    private static void indexSet(Object container, Object key, Object value) {
        if (container instanceof List<?> list) {
            PrlValues.asList(list).set(indexOf(key, list.size()), value);
            return;
        }
        if (container instanceof Map<?, ?> map) {
            PrlValues.asMap(map).put(key, value);
            return;
        }
        throw new PrlExecutionException("下标赋值需要 list/map，实际为 " + PrlValues.describe(container));
    }

    private static int indexOf(Object key, int size) {
        long index = PrlValues.asLong(key);
        if (index < 0 || index >= size) {
            throw new PrlExecutionException("下标 " + index + " 越界（长度 " + size + "）");
        }
        return (int) index;
    }

    /** {@code a..b} 左闭右闭（§2.2.4 只给了 {@code 1..10} 这一种写法，按闭区间解释）。 */
    private static List<Object> range(long start, long end) {
        if (end < start) {
            return new ArrayList<>();
        }
        long size = end - start + 1;
        if (size > MAX_COLLECTION_SIZE) {
            throw new PrlExecutionException("范围 " + start + ".." + end + " 产生 " + size
                    + " 个元素，超过上限 " + MAX_COLLECTION_SIZE + "（§2.11.1 L2）");
        }
        List<Object> values = new ArrayList<>((int) size);
        for (long value = start; value <= end; value++) {
            values.add(value);
        }
        return values;
    }
}