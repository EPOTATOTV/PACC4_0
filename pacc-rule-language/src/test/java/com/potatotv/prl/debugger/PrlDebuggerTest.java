package com.potatotv.prl.debugger;

import com.potatotv.prl.PrlException;
import com.potatotv.prl.bytecode.PrlBytecode;
import com.potatotv.prl.compiler.PrlCompiler;
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.runtime.PrlExecutionException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调试器测试（设计文档 §2.15.1：断点、单步执行、变量查看、调用栈、条件断点、日志点）。
 *
 * <p>断点是否停对位置，用「变量还没落地」来验证：在第 6 行 {@code let total = sum(events)} 上下断点时，
 * {@code total} 必须还不在槽位表里；若钩子挂在指令执行之后，这里会看到已经算好的值，
 * 那就意味着断点停在了这一行<strong>之后</strong>，单步调试会看不到副作用发生前的现场。</p>
 */
class PrlDebuggerTest {

    private static final String SOURCE = """
            rule "debug_me" {
                input {
                    events: list[int]
                    threshold: int
                }
                let total = sum(events)

                when:
                    total > threshold

                then:
                    emit_alert(
                        type = "DEBUG",
                        confidence = 0.5,
                        evidence = {
                            "total": total
                        }
                    )
                    record_evidence("total", total)
            }
            """;

    /** 运行期一定失败：2 个元素的列表取下标 5。 */
    private static final String FAILING = """
            rule "boom" {
                input {
                    events: list[int]
                }
                then:
                    record_evidence("x", events[5])
            }
            """;

    private static final long TIMEOUT_SECONDS = 3;

    // ------------------------------------------------------------------ 助手

    private static PrlBytecode compile(String source) {
        return new PrlCompiler().compile(source);
    }

    /** 取标记文本所在的行号（1 基）。测试里靠它定位断点，避免把行号写死后源码一改就错位。 */
    private static int lineOf(String source, String marker) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i + 1;
            }
        }
        throw new IllegalArgumentException("源码里找不到 '" + marker + "'");
    }

    private static Map<String, Object> input(long threshold) {
        return Map.of("events", List.of(10L, 20L), "threshold", threshold);
    }

    private static DebugState pause(PrlDebugger debugger) {
        Optional<DebugState> state = debugger.awaitPause(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertTrue(state.isPresent(), () -> "规则没有停在断点上（是否已结束：" + !debugger.isRunning() + "）");
        return state.get();
    }

    /** 等调试线程收尾；`running` 是在被调试线程的 finally 里置回的，和事件到达之间有个窗口。 */
    private static void awaitIdle(PrlDebugger debugger) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (debugger.isRunning() && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
        assertFalse(debugger.isRunning(), "调试线程没有收尾");
    }

    // ------------------------------------------------------------------ 断点

    @Test
    void breakpointStopsBeforeTheStatementTakesEffect() {
        int line = lineOf(SOURCE, "let total = sum(events)");
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.setBreakpoint(line);
            debugger.start(compile(SOURCE), "debug_me", input(5));

            DebugState state = pause(debugger);
            assertEquals(line, state.line());
            assertEquals(1, state.depth());
            assertEquals("rule_debug_me", state.functionName());
            assertEquals(List.of("rule_debug_me"), state.callStack());

            // 输入形参在帧建立时就进了槽位表，规则自己的 let 还没有。
            assertTrue(state.variables().containsKey("events"), state.variables().toString());
            assertTrue(state.variables().containsKey("threshold"), state.variables().toString());
            assertFalse(state.variables().containsKey("total"),
                    () -> "断点停在了 let 之后，看不到求值前的现场：" + state.variables());

            debugger.resume();
            DetectionResult result = assertInstanceOf(DetectionResult.class,
                    debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("DEBUG", result.type());
            assertEquals(30L, result.evidence().get("total"));
        } finally {
            debugger.close();
        }
    }

    @Test
    void noBreakpointRunsToCompletion() {
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.start(compile(SOURCE), "debug_me", input(5));
            assertTrue(debugger.awaitPause(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
            assertInstanceOf(DetectionResult.class, debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            debugger.close();
        }
    }

    // ------------------------------------------------------------------ 条件断点

    @Test
    void conditionalBreakpointOnlyStopsWhenConditionHolds() {
        int line = lineOf(SOURCE, "let total = sum(events)");

        PrlDebugger notMet = new PrlDebugger();
        try {
            // 条件是调用方给的谓词：要编译一段 PRL 表达式得先知道 input 字段的宿主类型，
            // 那份类型信息只存在于管理端（§2.3.3），所以引擎这边收谓词而不是源码字符串。
            notMet.setBreakpoint(line, frame -> Long.valueOf(99L).equals(frame.variables().get("threshold")));
            notMet.start(compile(SOURCE), "debug_me", input(5));
            assertTrue(notMet.awaitPause(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty(), "条件不成立时不该中断");
            assertInstanceOf(DetectionResult.class, notMet.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            notMet.close();
        }

        PrlDebugger met = new PrlDebugger();
        try {
            met.setBreakpoint(line, frame -> Long.valueOf(5L).equals(frame.variables().get("threshold")));
            met.start(compile(SOURCE), "debug_me", input(5));
            assertEquals(line, pause(met).line());
            met.resume();
            assertInstanceOf(DetectionResult.class, met.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            met.close();
        }
    }

    // ------------------------------------------------------------------ 日志点

    @Test
    void logpointRecordsVariablesWithoutInterrupting() {
        int line = lineOf(SOURCE, "emit_alert(");
        List<LogRecord> pushed = new ArrayList<>();
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.addLogpoint(line, pushed::add);
            debugger.start(compile(SOURCE), "debug_me", input(5));

            DetectionResult result = assertInstanceOf(DetectionResult.class,
                    debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertEquals("DEBUG", result.type());

            assertFalse(debugger.isPaused(), "日志点不应该让执行停下来");
            assertFalse(debugger.logRecords().isEmpty(), "日志点没有留下记录");
            LogRecord record = debugger.logRecords().get(0);
            assertEquals(line, record.line());
            assertEquals("rule_debug_me", record.functionName());
            // 走到 emit_alert 时 let 已经执行过，槽位里应当有 total —— 和断点停在第 6 行的现场正好相反。
            assertEquals(30L, record.variables().get("total"));
            assertEquals(debugger.logRecords(), pushed);
        } finally {
            debugger.close();
        }
    }

    // ------------------------------------------------------------------ 单步

    @Test
    void stepOverPausesOnTheNextStatementInTheSameFrame() {
        int line = lineOf(SOURCE, "let total = sum(events)");
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.setBreakpoint(line);
            debugger.start(compile(SOURCE), "debug_me", input(5));
            assertEquals(line, pause(debugger).line());

            debugger.stepOver();
            DebugState next = pause(debugger);
            assertNotEquals(line, next.line(), "单步没有走到下一条语句");
            assertEquals(1, next.depth(), "入口函数里单步不应改变调用深度");

            debugger.resume();
            assertInstanceOf(DetectionResult.class, debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            debugger.close();
        }
    }

    @Test
    void stepIntoPausesOnTheNextLineMarker() {
        int line = lineOf(SOURCE, "emit_alert(");
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.setBreakpoint(line);
            debugger.start(compile(SOURCE), "debug_me", input(5));
            assertEquals(line, pause(debugger).line());

            debugger.stepInto();
            DebugState next = pause(debugger);
            assertNotEquals(line, next.line());
            assertTrue(next.depth() >= 1);

            debugger.resume();
            assertInstanceOf(DetectionResult.class, debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            debugger.close();
        }
    }

    @Test
    void stepOutRunsUntilTheFrameIsPopped() {
        int line = lineOf(SOURCE, "let total = sum(events)");
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.setBreakpoint(line);
            debugger.start(compile(SOURCE), "debug_me", input(5));
            assertEquals(line, pause(debugger).line());

            // 已经是最外层帧，step out 等于放行到底：不会再有任何暂停。
            debugger.stepOut();
            assertTrue(debugger.awaitPause(TIMEOUT_SECONDS, TimeUnit.SECONDS).isEmpty());
            assertInstanceOf(DetectionResult.class, debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            debugger.close();
        }
    }

    @Test
    void steppingWithoutPausingIsRejected() {
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.start(compile(SOURCE), "debug_me", input(5));
            assertThrows(PrlException.class, debugger::stepOver,
                    "没有停在断点上还允许单步，会让控制方以为步进生效了");
            debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            debugger.close();
        }
    }

    // ------------------------------------------------------------------ 中止与失败

    @Test
    void abortStopsTheExecution() {
        int line = lineOf(SOURCE, "let total = sum(events)");
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.setBreakpoint(line);
            debugger.start(compile(SOURCE), "debug_me", input(5));
            assertEquals(line, pause(debugger).line());

            debugger.abort();
            assertThrows(PrlDebugAbortedException.class,
                    () -> debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            awaitIdle(debugger);
        } finally {
            debugger.close();
        }
    }

    @Test
    void failureDuringExecutionIsReportedToTheController() {
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.start(compile(FAILING), "boom", Map.of("events", List.of(1L, 2L)));
            assertThrows(PrlExecutionException.class,
                    () -> debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            awaitIdle(debugger);
        } finally {
            debugger.close();
        }
    }

    @Test
    void aSecondStartOnARunningDebuggerIsRejected() {
        int line = lineOf(SOURCE, "let total = sum(events)");
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.setBreakpoint(line);
            debugger.start(compile(SOURCE), "debug_me", input(5));
            assertEquals(line, pause(debugger).line());
            assertThrows(PrlException.class,
                    () -> debugger.start(compile(SOURCE), "debug_me", input(5)));
            debugger.resume();
            debugger.awaitFinish(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } finally {
            debugger.close();
        }
    }

    // ------------------------------------------------------------------ 断点清单

    @Test
    void breakpointsAndLogpointsAreListedAndRemoved() {
        PrlDebugger debugger = new PrlDebugger();
        try {
            debugger.setBreakpoint(3);
            debugger.setBreakpoint(5, frame -> true);
            debugger.addLogpoint(7);

            assertEquals(List.of(new Breakpoint(3, false, false),
                            new Breakpoint(5, false, true),
                            new Breakpoint(7, true, false)),
                    debugger.breakpoints());

            // 同一行上断点与日志点互斥：一行只能有一种，否则「日志点不中断」这条约束就没法保证了。
            debugger.addLogpoint(3);
            assertEquals(new Breakpoint(3, true, false), debugger.breakpoints().get(0));

            assertTrue(debugger.removeBreakpoint(5));
            assertFalse(debugger.breakpoints().contains(new Breakpoint(5, false, true)));
            assertFalse(debugger.removeBreakpoint(5));
        } finally {
            debugger.close();
        }
    }

    @Test
    void closeIsSafeBeforeAnyRun() {
        PrlDebugger debugger = new PrlDebugger();
        debugger.clearBreakpoints();
        assertFalse(debugger.isRunning());
        assertTrue(debugger.state().isEmpty());
        debugger.close();
        debugger.close();
    }
}