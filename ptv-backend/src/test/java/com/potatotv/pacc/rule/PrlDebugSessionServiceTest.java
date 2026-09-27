package com.potatotv.pacc.rule;

import com.potatotv.prl.PrlException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 调试会话注册表的测试（设计文档 §2.15.1 / §3.3.3）。
 *
 * <p>跑的是真编译器 + 真 {@code PrlDebugger}，只有「谁来调」这一层是假的。要钉住的是三件事：
 * 断点确实把执行停下来了、单步与继续之后会话状态没跑偏、以及失败的被调试规则不会让请求线程
 * 卡在等待里 —— 这三条任一不成立，管理端的调试面板就会表现成「点了没反应」。</p>
 */
class PrlDebugSessionServiceTest {

    private static final String RULE = "probe_rule";

    private final PrlDebugSessionService service = new PrlDebugSessionService();

    // ------------------------------------------------------------------ 断点与现场

    @Test
    void 会话在断点处暂停并给出变量与调用栈() {
        // 断点落在 let marked：那一刻 factor 已经算完、marked 还没绑定，input 与 local 两档都能断言到
        int breakLine = lineOf(SOURCE, "let marked");

        Map<String, Object> state = service.create(RULE, SOURCE, "1.0.0",
                List.of(breakpoint(breakLine)), Map.of("reputation", 10.0));

        assertEquals("paused", state.get("status"));
        assertNotEquals("", state.get("sessionId"), "暂停中的会话必须给出 sessionId，否则面板点不动");
        assertEquals(breakLine, state.get("currentLine"));
        assertEquals(RULE, state.get("ruleName"));
        assertEquals("1.0.0", state.get("ruleVersion"));
        assertEquals(false, state.get("isDemo"), "后端结果不能标成演示数据");

        // input 块的字段按宿主传入的键划成 input 档，其余是 let 局部变量
        Map<?, ?> reputation = variable(state, "reputation");
        assertEquals("input", reputation.get("scope"));
        assertEquals("float", reputation.get("type"));

        Map<?, ?> factor = variable(state, "factor");
        assertEquals("local", factor.get("scope"));

        assertFalse(((List<?>) state.get("callStack")).isEmpty(), "暂停时应当有栈帧");
        assertFalse(outputMessages(state).isEmpty(), "会话创建也要留一条日志");
    }

    @Test
    void 单步之后继续执行可以把规则跑完并进入停止态() {
        String sessionId = pausedSession();

        Map<String, Object> stepped = service.step(sessionId, "over");
        assertNotEquals("error", stepped.get("status"), "单步不该把规则搞错：" + outputMessages(stepped));

        Map<String, Object> resumed = service.resume(sessionId);
        assertEquals("stopped", resumed.get("status"));
        assertEquals("", resumed.get("sessionId"), "终态不给会话号，前端据此放开「开始会话」");
        assertTrue(outputMessages(resumed).stream().anyMatch(message -> message.contains("规则执行结束")),
                "跑完了要留一条可见的日志：" + outputMessages(resumed));
    }

    @Test
    void 进入单步同样停在执行流里() {
        String sessionId = pausedSession();

        Map<String, Object> stepped = service.step(sessionId, "into");

        assertNotEquals("error", stepped.get("status"));
        assertNotEquals("stopped", stepped.get("status"), "还有语句没走完，不该直接结束");
    }

    @Test
    void 停止会话后句柄被回收再发命令会报会话不存在() {
        String sessionId = pausedSession();

        Map<String, Object> stopped = service.stop(sessionId);
        assertEquals("stopped", stopped.get("status"));
        assertEquals("", stopped.get("sessionId"));

        PrlException error = assertThrows(PrlException.class, () -> service.resume(sessionId));
        assertTrue(error.getMessage().contains("会话不存在"), error.getMessage());
    }

    // ------------------------------------------------------------------ 断点表

    @Test
    void 没有断点时规则一路跑完() {
        Map<String, Object> state = service.create(RULE, SOURCE, "1.0.0", List.of(), Map.of("reputation", 10.0));

        assertEquals("stopped", state.get("status"));
    }

    @Test
    void 条件断点退化成无条件断点日志点按日志点计() {
        int conditionalLine = lineOf(SOURCE, "let factor");
        int logpointLine = lineOf(SOURCE, "let marked");

        // 条件只有编辑器编译好的 Predicate 才可用，服务端手里只有一个源码字符串，按无条件断点处理
        Map<String, Object> state = service.create(RULE, SOURCE, "1.0.0",
                List.of(breakpoint(conditionalLine), logpoint(logpointLine)), Map.of("reputation", 10.0));
        assertEquals("paused", state.get("status"), "条件断点至少要停下来");

        List<Map<String, Object>> breakpoints = service.updateBreakpoints((String) state.get("sessionId"),
                List.of(logpoint(logpointLine)));
        assertEquals(1, breakpoints.size());
        assertEquals(logpointLine, breakpoints.get(0).get("line"));
        assertEquals(true, breakpoints.get(0).get("logPoint"));
        assertEquals(RULE + ":" + logpointLine, breakpoints.get(0).get("id"));
        assertEquals(RULE, breakpoints.get(0).get("ruleName"));
    }

    @Test
    void 日志点不中断执行并把变量写进执行日志() {
        int logpointLine = lineOf(SOURCE, "let factor");

        Map<String, Object> state = service.create(RULE, SOURCE, "1.0.0",
                List.of(logpoint(logpointLine)), Map.of("reputation", 10.0));

        assertEquals("stopped", state.get("status"), "日志点不该让执行停下来");
        assertTrue(outputMessages(state).stream().anyMatch(message -> message.contains("日志点")),
                "日志点的变量值要出现在执行日志里：" + outputMessages(state));
    }

    @Test
    void 停用的断点不生效但照样回显给编辑器() {
        int stopLine = lineOf(SOURCE, "let marked");
        int offLine = lineOf(SOURCE, "let factor");

        Map<String, Object> state = service.create(RULE, SOURCE, "1.0.0",
                List.of(breakpoint(stopLine), disabled(offLine)), Map.of("reputation", 10.0));
        assertEquals("paused", state.get("status"));
        assertEquals(stopLine, state.get("currentLine"), "停用的那条不该把执行拦下来");

        String sessionId = (String) state.get("sessionId");
        try {
            List<Map<String, Object>> breakpoints = service.updateBreakpoints(sessionId,
                    List.of(breakpoint(stopLine), disabled(offLine)));

            // 启停状态归编辑器维护：只回生效的那几条，编辑器一刷新就把停用的断点丢了
            assertEquals(2, breakpoints.size());
            assertEquals(true, rowAt(breakpoints, stopLine).get("enabled"));
            assertEquals(false, rowAt(breakpoints, offLine).get("enabled"));
        } finally {
            service.stop(sessionId);
        }
    }

    // ------------------------------------------------------------------ 失败与边界

    @Test
    void 被调试规则执行失败时如实报错而不让请求线程卡住() {
        Map<String, Object> state = service.create(RULE, FAILING_SOURCE, "1.0.0", List.of(),
                Map.of("reputation", 0.0));

        assertEquals("error", state.get("status"));
        assertEquals("", state.get("sessionId"));
        assertTrue(outputMessages(state).stream()
                        .anyMatch(message -> message.contains("规则执行失败")),
                "失败原因要写进执行日志：" + outputMessages(state));
    }

    @Test
    void 源码里没有这条规则时拒绝开会话() {
        PrlException error = assertThrows(PrlException.class,
                () -> service.create("other_rule", SOURCE, "1.0.0", List.of(), Map.of()));

        assertTrue(error.getMessage().contains("other_rule"), error.getMessage());
    }

    @Test
    void 并发会话达到上限时明确拒绝而不是排队() {
        List<String> live = new ArrayList<>();
        try {
            for (int i = 0; i < PrlDebugSessionService.MAX_SESSIONS; i++) {
                Map<String, Object> state = service.create(RULE, SOURCE, "1.0.0",
                        List.of(breakpoint(lineOf(SOURCE, "let factor"))), Map.of("reputation", 10.0));
                live.add((String) state.get("sessionId"));
            }

            PrlException error = assertThrows(PrlException.class,
                    () -> service.create(RULE, SOURCE, "1.0.0", List.of(), Map.of()));

            assertTrue(error.getMessage().contains("上限"), error.getMessage());
        } finally {
            // 停掉留下的会话，别让 4 个被调试线程跟着整个测试 JVM 跑
            live.forEach(service::stop);
        }
    }

    // ------------------------------------------------------------------ 辅助

    /** 开一条停在 {@code let marked} 上的会话，返回会话号。 */
    private String pausedSession() {
        Map<String, Object> state = service.create(RULE, SOURCE, "1.0.0",
                List.of(breakpoint(lineOf(SOURCE, "let marked"))), Map.of("reputation", 10.0));
        assertEquals("paused", state.get("status"));
        return (String) state.get("sessionId");
    }

    private static Map<String, Object> breakpoint(int line) {
        return Map.of("line", line, "enabled", true);
    }

    private static Map<String, Object> logpoint(int line) {
        return Map.of("line", line, "enabled", true, "logPoint", true);
    }

    private static Map<String, Object> disabled(int line) {
        return Map.of("line", line, "enabled", false);
    }

    private static Map<String, Object> rowAt(List<Map<String, Object>> rows, int line) {
        for (Map<String, Object> row : rows) {
            if (Integer.valueOf(line).equals(row.get("line"))) {
                return row;
            }
        }
        throw new AssertionError("断点表里没有第 " + line + " 行：" + rows);
    }

    private static int lineOf(String source, String marker) {
        String[] lines = source.split("\n");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i + 1;
            }
        }
        throw new AssertionError("源码里没有 " + marker);
    }

    private static Map<?, ?> variable(Map<String, Object> state, String name) {
        for (Object item : (List<?>) state.get("variables")) {
            Map<?, ?> row = (Map<?, ?>) item;
            if (name.equals(row.get("name"))) {
                return row;
            }
        }
        throw new AssertionError("变量里没有 " + name + "：" + state.get("variables"));
    }

    private static List<String> outputMessages(Map<String, Object> state) {
        List<String> messages = new ArrayList<>();
        for (Object item : (List<?>) state.get("output")) {
            messages.add(String.valueOf(((Map<?, ?>) item).get("message")));
        }
        return messages;
    }

    /** 与 {@code rules/account_history.prl} 同构的调试用例，断点打算落在 {@code let factor} 那一行。 */
    private static final String SOURCE = """
            rule "probe_rule" {
                version: "1.0.0"
                author: "tester"
                severity: low
                category: "test"
                description: "调试会话用例"
                enabled: true

                input {
                    reputation: float
                }

                let factor = (100.0 - reputation) / 100.0
                let marked = factor >= 0.3

                when:
                    marked

                then:
                    emit_alert(
                        type = "probe_rule",
                        confidence = factor,
                        evidence = {
                            "score": factor * 8.0,
                            "reason": "调试会话"
                        }
                    )
            }
            """;

    /**
     * 一定跑不完的规则：除数是 0，VM 会抛「浮点数除以零」。
     *
     * <p>刻意不靠「宿主少传一个 input」来制造失败：那种写法是否报错取决于 VM 对缺失参数的宽容度，
     * 换个实现就不再失败了，测试会变成一条永远绿的假断言。也不能拿 {@code to_float(字符串)} 制造
     * 失败 —— 那是编译期就能查出来的类型错误，压根走不到调试会话里。</p>
     */
    private static final String FAILING_SOURCE = """
            rule "probe_rule" {
                version: "1.0.0"
                author: "tester"
                severity: low
                category: "test"
                description: "必定失败的调试用例"
                enabled: true

                input {
                    reputation: float
                }

                let factor = 100.0 / reputation

                when:
                    factor > 0.0

                then:
                    emit_alert(
                        type = "probe_rule",
                        confidence = factor,
                        evidence = { "reason": "调试会话" }
                    )
            }
            """;
}