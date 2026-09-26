package com.potatotv.prl.debugger;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.engine.RuleInstance;
import com.potatotv.prl.runtime.PrlExecutionObserver;
import com.potatotv.prl.runtime.PrlFrameView;
import com.potatotv.prl.sandbox.PrlHostContext;
import com.potatotv.prl.vm.PrlVm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * PRL 调试器（设计文档 §2.15.1）。
 *
 * <p>它同时是 {@link PrlExecutionObserver} 与给控制方用的句柄：被调试的规则在
 * {@link #start} 起的线程里跑，命中暂停条件时该线程<strong>阻塞</strong>在命令队列上，
 * 控制线程（管理端请求线程）通过 {@link #awaitPause} 拿现场、用
 * {@link #resume}/{@link #stepInto}/{@link #stepOver}/{@link #stepOut} 放行。</p>
 *
 * <p>为什么用两个阻塞队列而不是回调式 API：调试天然是「跑起来 → 停下来等指令」的两线程模型。
 * 回调式要求宿主在执行线程里处理 UI 交互，或者把执行搬到状态机里 —— 前者会把宿主请求线程
 * 连同整个连接池一起挂住，后者等于把 VM 重写成可以随时保存恢复的协程。队列方案两个都不用改。</p>
 *
 * <p>断点的粒度是<strong>源码行</strong>而不是字节码指令：一条 {@code let} 展开成十几条指令，
 * 按指令停会让编辑器里的「单步」在同一个表达式上停十几次。VM 只在 {@code LINE} 标记处回调，
 * 所以这里的断点、单步、日志点全部以行为单位。</p>
 *
 * <p>条件断点的条件是调用方给的 {@link Predicate}，不是 PRL 源码字符串。要编译一段 PRL 表达式，
 * 得先知道 {@code input} 块里每个字段的宿主类型（§2.3.3），而这份类型信息只有管理端编辑器手里有
 * —— 引擎这边凭空猜一个类型名，猜错就是编译期报错，猜成 {@code any} 又会让条件里的成员访问全部落空。
 * 所以条件由编辑器编译好再传进来。</p>
 *
 * <p>非线程安全的地方只有一处：同一条规则不能被两个 {@link #start} 同时调试，重复调用会抛异常。</p>
 */
public final class PrlDebugger implements PrlExecutionObserver {

    /** 日志点最多保留这么多条记录；规则里的日志点落在循环里时，不设上限会把堆吃光。 */
    public static final int MAX_LOG_RECORDS = 1000;

    /** 单步方式。 */
    public enum StepMode {
        /** 只在断点上停下。 */
        CONTINUE,
        /** 下一条语句就停（进函数）。 */
        STEP_INTO,
        /** 下一条当前帧的语句才停（不进函数）。 */
        STEP_OVER,
        /** 当前函数返回时停。 */
        STEP_OUT
    }

    private static final Predicate<PrlFrameView> ALWAYS = frame -> true;
    private static final Consumer<LogRecord> NO_SINK = record -> {
    };

    private final PrlHostContext host;

    /** 断点：行号 → 条件谓词。 */
    private final Map<Integer, Predicate<PrlFrameView>> breakpoints = new ConcurrentHashMap<>();

    /** 日志点：行号 → 记录回调。 */
    private final Map<Integer, Consumer<LogRecord>> logpoints = new ConcurrentHashMap<>();

    private final List<LogRecord> logRecords = Collections.synchronizedList(new ArrayList<>());
    private final BlockingQueue<Event> events = new LinkedBlockingQueue<>();
    private final BlockingQueue<Command> commands = new LinkedBlockingQueue<>();

    private final AtomicBoolean aborted = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    /** 被调试线程退出后留下的事件，让 {@link #awaitFinish} 能被调用多次。 */
    private volatile Event terminal;

    private volatile Thread worker;
    private volatile DebugState state;
    private volatile boolean paused;

    private volatile StepMode mode = StepMode.CONTINUE;
    private volatile int resumeDepth;
    private volatile int resumeLine;

    public PrlDebugger() {
        this(PrlHostContext.EMPTY);
    }

    public PrlDebugger(PrlHostContext host) {
        this.host = host == null ? PrlHostContext.EMPTY : host;
    }

    // ------------------------------------------------------------------ 断点管理

    /** 在第 {@code line} 行下无条件断点。 */
    public void setBreakpoint(int line) {
        requireLine(line);
        logpoints.remove(line);
        breakpoints.put(line, ALWAYS);
    }

    /** 在第 {@code line} 行下条件断点（§2.15.1 的「条件断点」）：条件为真才中断。 */
    public void setBreakpoint(int line, Predicate<PrlFrameView> condition) {
        requireLine(line);
        if (condition == null) {
            setBreakpoint(line);
            return;
        }
        logpoints.remove(line);
        breakpoints.put(line, condition);
    }

    /** 在第 {@code line} 行下日志点：不中断，只把变量值记进 {@link #logRecords()}。 */
    public void addLogpoint(int line) {
        addLogpoint(line, NO_SINK);
    }

    /** 在第 {@code line} 行下日志点，并把记录同时交给 {@code sink}（管理端直接推给前端用）。 */
    public void addLogpoint(int line, Consumer<LogRecord> sink) {
        requireLine(line);
        breakpoints.remove(line);
        logpoints.put(line, sink == null ? NO_SINK : sink);
    }

    /** 去掉某一行的断点或日志点；返回这一行上原本有没有东西。 */
    public boolean removeBreakpoint(int line) {
        boolean hadLogpoint = logpoints.remove(line) != null;
        boolean hadBreakpoint = breakpoints.remove(line) != null;
        return hadLogpoint || hadBreakpoint;
    }

    public void clearBreakpoints() {
        breakpoints.clear();
        logpoints.clear();
    }

    /** 当前全部断点与日志点，按行号升序。 */
    public List<Breakpoint> breakpoints() {
        Map<Integer, Breakpoint> all = new TreeMap<>();
        for (Map.Entry<Integer, Predicate<PrlFrameView>> entry : breakpoints.entrySet()) {
            all.put(entry.getKey(),
                    new Breakpoint(entry.getKey(), false, entry.getValue() != ALWAYS));
        }
        for (Integer line : logpoints.keySet()) {
            all.put(line, new Breakpoint(line, true, false));
        }
        return List.copyOf(all.values());
    }

    public List<LogRecord> logRecords() {
        synchronized (logRecords) {
            return List.copyOf(logRecords);
        }
    }

    public void clearLogRecords() {
        logRecords.clear();
    }

    // ------------------------------------------------------------------ 会话

    /**
     * 在后台线程里执行规则并开始调试，立即返回。
     *
     * @param ruleName 规则名；为 {@code null} 或字节码里没有这条规则时执行入口函数
     */
    public void start(PrlBytecode program, String ruleName, Map<String, Object> input) {
        if (program == null) {
            throw new PrlException("没有可调试的字节码");
        }
        if (!running.compareAndSet(false, true)) {
            throw new PrlException("调试器同一时刻只能调试一次执行，请先 close() 或等它结束");
        }
        aborted.set(false);
        terminal = null;
        state = null;
        paused = false;
        mode = StepMode.CONTINUE;
        // 上一次会话如果没被 awaitFinish 取走结束事件，它会留在队列里被下一次 awaitPause 当成
        // 这次的现场。开新会话先清空两个队列。
        events.clear();
        commands.clear();
        Thread thread = new Thread(() -> execute(program, ruleName, input), "prl-debugger");
        thread.setDaemon(true);
        worker = thread;
        thread.start();
    }

    public void start(PrlBytecode program, Map<String, Object> input) {
        start(program, null, input);
    }

    public void start(RuleInstance rule, Map<String, Object> input) {
        start(rule.bytecode(), rule.name(), input);
    }

    private void execute(PrlBytecode program, String ruleName, Map<String, Object> input) {
        PrlVm vm = new PrlVm(host, this);
        try {
            Object result = ruleName != null && program.rule(ruleName) != null
                    ? vm.executeRule(program, ruleName, input)
                    : vm.execute(program, input);
            publish(new Finished(result));
        } catch (PrlDebugAbortedException e) {
            publish(new Aborted());
        } catch (Throwable e) {
            publish(new Failed(e));
        } finally {
            paused = false;
            running.set(false);
        }
    }

    /**
     * 等下一次暂停。
     *
     * @return 现场快照；执行已经结束、被中止，或超时未暂停时为空。
     *         空返回值要用 {@link #isRunning()} 区分「还没停」和「已经跑完」
     */
    public Optional<DebugState> awaitPause(long timeout, TimeUnit unit) {
        long deadline = deadline(timeout, unit);
        while (true) {
            Event event = poll(deadline);
            if (event == null) {
                return Optional.empty();
            }
            if (event instanceof Paused pausedEvent) {
                return Optional.of(pausedEvent.state());
            }
            terminal = event;
            return Optional.empty();
        }
    }

    /**
     * 等这次执行结束。
     *
     * @return 规则的返回值，没有告警时为 {@code null}
     * @throws PrlException 规则执行失败（原样抛出），或等待期间规则停在断点上（那需要先放行）
     */
    public Object awaitFinish(long timeout, TimeUnit unit) {
        long deadline = deadline(timeout, unit);
        Event event = terminal;
        while (event == null) {
            event = poll(deadline);
            if (event == null) {
                throw new PrlException("等待规则执行结束超时");
            }
            if (event instanceof Paused) {
                throw new PrlException("规则停在断点上，先 resume() 或单步一次再等它结束");
            }
            terminal = event;
        }
        if (event instanceof Finished finished) {
            return finished.result();
        }
        if (event instanceof Failed failed) {
            if (failed.error() instanceof PrlException prl) {
                throw prl;
            }
            throw new PrlException("规则执行失败：" + failed.error(), failed.error());
        }
        throw new PrlDebugAbortedException("调试会话已中止，规则没有跑完");
    }

    /** 放行到下一个断点（§2.15.1 的 continue）。 */
    public void resume() {
        command(Command.CONTINUE);
    }

    public void stepInto() {
        command(Command.STEP_INTO);
    }

    public void stepOver() {
        command(Command.STEP_OVER);
    }

    public void stepOut() {
        command(Command.STEP_OUT);
    }

    /** 中止这次执行：被调试线程在下一条语句处退出，或者立刻从暂停中醒来。 */
    public void abort() {
        aborted.set(true);
        commands.offer(Command.ABORT);
        Thread thread = worker;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /** 关掉调试会话：还在跑就先中止，再等被调试线程收尾。 */
    public void close() {
        if (running.get()) {
            abort();
        }
        Thread thread = worker;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        commands.clear();
        events.clear();
        worker = null;
    }

    public boolean isRunning() {
        return running.get();
    }

    public boolean isPaused() {
        return paused;
    }

    /** 最近一次暂停的现场；从未暂停过时为空。 */
    public Optional<DebugState> state() {
        return Optional.ofNullable(state);
    }

    // ------------------------------------------------------------------ 观察者回调

    @Override
    public void onInstruction(PrlFrameView frame) {
        if (aborted.get()) {
            throw new PrlDebugAbortedException("调试会话已中止");
        }
        int line = frame.line();
        Consumer<LogRecord> sink = logpoints.get(line);
        if (sink != null) {
            // 日志点不中断（§2.15.1），单步走到它上面也一样继续 —— 它的用途就是「看一眼再走」。
            record(sink, frame, line);
            return;
        }
        if (!hit(frame, line) && !shouldStep(frame)) {
            return;
        }
        pause(frame);
    }

    private boolean hit(PrlFrameView frame, int line) {
        Predicate<PrlFrameView> condition = breakpoints.get(line);
        return condition != null && condition.test(frame);
    }

    /**
     * 单步判定。
     *
     * <p>{@code STEP_OVER} 额外的「行号不同」条件是为了跳过循环头：{@code while} 那一行在每次迭代
     * 开始时都会产生一次行标记，只看深度的话，从循环头单步一次会原地停在循环头。</p>
     */
    private boolean shouldStep(PrlFrameView frame) {
        return switch (mode) {
            case STEP_INTO -> true;
            case STEP_OVER -> frame.depth() <= resumeDepth && frame.line() != resumeLine;
            case STEP_OUT -> frame.depth() < resumeDepth;
            case CONTINUE -> false;
        };
    }

    private void record(Consumer<LogRecord> sink, PrlFrameView frame, int line) {
        LogRecord record = new LogRecord(line, frame.functionName(), frame.variables());
        synchronized (logRecords) {
            // 只保留前 MAX_LOG_RECORDS 条：日志点落在循环里时记录会无限增长，管理端也读不过来。
            if (logRecords.size() < MAX_LOG_RECORDS) {
                logRecords.add(record);
            }
        }
        sink.accept(record);
    }

    private void pause(PrlFrameView frame) {
        state = DebugState.snapshot(frame);
        paused = true;
        events.offer(new Paused(state));
        Command command = take();
        paused = false;
        apply(command, frame);
    }

    private Command take() {
        try {
            return commands.take();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PrlDebugAbortedException("调试线程被中断，会话中止");
        }
    }

    private void apply(Command command, PrlFrameView frame) {
        switch (command) {
            case CONTINUE -> mode = StepMode.CONTINUE;
            case STEP_INTO -> mode = StepMode.STEP_INTO;
            case STEP_OVER -> {
                resumeDepth = frame.depth();
                resumeLine = frame.line();
                mode = StepMode.STEP_OVER;
            }
            case STEP_OUT -> {
                resumeDepth = frame.depth();
                mode = StepMode.STEP_OUT;
            }
            case ABORT -> throw new PrlDebugAbortedException("调试会话已中止");
        }
    }

    private void command(Command command) {
        if (!paused) {
            throw new PrlException("规则没有停在断点上，无法" + switch (command) {
                case CONTINUE -> "放行";
                case STEP_INTO, STEP_OVER, STEP_OUT -> "单步";
                case ABORT -> "中止";
            });
        }
        commands.offer(command);
    }

    // ------------------------------------------------------------------ 内部

    private void publish(Event event) {
        if (!(event instanceof Paused)) {
            terminal = event;
        }
        events.offer(event);
    }

    private Event poll(long deadlineNanos) {
        long remaining = deadlineNanos - System.nanoTime();
        if (remaining <= 0) {
            return null;
        }
        try {
            return events.poll(remaining, TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static long deadline(long timeout, TimeUnit unit) {
        return System.nanoTime() + unit.toNanos(timeout);
    }

    private static void requireLine(int line) {
        if (line <= 0) {
            throw new PrlException("行号必须从 1 开始，收到 " + line);
        }
    }

    /** 被调试线程发给控制线程的事件。 */
    private interface Event {
    }

    private record Paused(DebugState state) implements Event {
    }

    private record Finished(Object result) implements Event {
    }

    private record Failed(Throwable error) implements Event {
    }

    private record Aborted() implements Event {
    }

    /** 控制线程发给被调试线程的命令。 */
    private enum Command {
        CONTINUE, STEP_INTO, STEP_OVER, STEP_OUT, ABORT
    }
}