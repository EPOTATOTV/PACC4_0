package com.potatotv.pacc.controller;

import com.potatotv.pacc.rule.PrlDebugSessionService;
import com.potatotv.pacc.rule.PrlRuleEngine;
import com.potatotv.prl.PrlException;
import com.potatotv.prl.profiler.PrlProfiler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 调试与性能端点（设计文档 §3.3.3）的响应契约。
 *
 * <p>与 {@code PrlAdminControllerTest} 同一个思路：塞给编辑器的字段名、包装层与单位在这里钉死。
 * 会话服务本身由 {@code PrlDebugSessionServiceTest} 用真引擎覆盖，这里只保证「包没包 {@code state}」
 * 「毫秒还是纳秒」「百分比是 0-100 还是 0-1」这类一改就白屏的约定。</p>
 */
class PrlDebugControllerTest {

    private static final String RULE = "probe_rule";

    private final PrlDebugSessionService sessions = mock(PrlDebugSessionService.class);
    private final PrlRuleEngine ruleEngine = mock(PrlRuleEngine.class);
    private final PrlDebugController controller = new PrlDebugController(sessions, ruleEngine);

    // ------------------------------------------------------------------ 调试

    @Test
    void 开会话把状态包在state里缺少源码直接拒绝() {
        when(sessions.create(eq(RULE), any(), any(), any(), any())).thenReturn(state("paused"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleName", RULE);
        body.put("source", "rule \"probe_rule\" { }");
        body.put("breakpoints", List.of(Map.of("line", 3, "enabled", true)));

        ResponseEntity<?> ok = controller.createSession(body);
        assertEquals(HttpStatus.OK, ok.getStatusCode());
        assertEquals("paused", ((Map<?, ?>) ((Map<?, ?>) ok.getBody()).get("state")).get("status"));

        body.remove("source");
        ResponseEntity<?> rejected = controller.createSession(body);
        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        verify(sessions, never()).create(eq(RULE), eq(""), any(), any(), any());
    }

    @Test
    void 单步把mode透传下去() {
        when(sessions.step(eq("s-1"), eq("over"))).thenReturn(state("paused"));

        ResponseEntity<?> resp = controller.step(Map.of("sessionId", "s-1", "mode", "over"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(sessions).step("s-1", "over");
    }

    @Test
    void 缺少会话号时统一四百而不是把null透给服务层() {
        assertEquals(HttpStatus.BAD_REQUEST, controller.step(Map.of()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.resume(Map.of()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.stop(Map.of()).getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, controller.breakpoints(Map.of()).getStatusCode());
        verify(sessions, never()).resume(any());
        verify(sessions, never()).stop(any());
    }

    @Test
    void 会话不存在时把服务层的错误如实回给前端() {
        when(sessions.resume(eq("gone"))).thenThrow(new PrlException("调试会话不存在或已结束，请重新开始会话"));

        ResponseEntity<?> resp = controller.resume(Map.of("sessionId", "gone"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        assertTrue(String.valueOf(((Map<?, ?>) resp.getBody()).get("error")).contains("会话不存在"));
    }

    @Test
    void 断点表包在breakpoints里() {
        when(sessions.updateBreakpoints(eq("s-1"), any())).thenReturn(List.of(
                Map.of("id", RULE + ":9", "ruleName", RULE, "line", 9, "enabled", true, "logPoint", true)));

        Map<?, ?> out = (Map<?, ?>) controller.breakpoints(
                Map.of("sessionId", "s-1", "breakpoints", List.of(Map.of("line", 9)))).getBody();

        Map<?, ?> one = (Map<?, ?>) ((List<?>) out.get("breakpoints")).get(0);
        assertEquals(RULE + ":9", one.get("id"));
        assertEquals(true, one.get("logPoint"));
    }

    // ------------------------------------------------------------------ 性能

    @Test
    void 性能报告按编辑器单位给出毫秒与千字节() {
        PrlProfiler profiler = new PrlProfiler();
        profiler.register(RULE, "1.0.0", 4096L);
        profiler.onRuleStart(RULE);
        profiler.onEnterFunction("helper");
        profiler.onExitFunction("helper", 3_000_000L);
        profiler.onRuleEnd(RULE, 5_000_000L);
        when(ruleEngine.profiler()).thenReturn(profiler);

        Map<?, ?> out = (Map<?, ?>) controller.profile(RULE, null, null).getBody();

        assertEquals(RULE, out.get("ruleName"));
        assertEquals("1.0.0", out.get("ruleVersion"));
        assertEquals(1L, out.get("executions"));
        assertEquals(5.0, out.get("avgMs"), "纳秒要换成毫秒");
        assertEquals(5.0, out.get("maxMs"));
        assertEquals(4.0, out.get("memoryPeakKb"), "字节要换成千字节");
        assertEquals("进程启动至今", out.get("window"));

        Map<?, ?> hotspot = (Map<?, ?>) ((List<?>) out.get("hotspots")).get(0);
        assertEquals("helper", hotspot.get("name"));
        assertEquals(100.0, hotspot.get("percent"), "占比是 0-100 的百分数，不是 0-1");
        assertEquals(3.0, hotspot.get("totalMs"));
        assertTrue(String.valueOf(hotspot.get("suggestion")).contains("helper"),
                "优化建议提到哪个函数就挂到那一行上");
        assertTrue(((List<?>) out.get("suggestions")).size() >= 1);
    }

    @Test
    void 没有采样的规则给全零而不是报错版本号回退到查询参数() {
        PrlProfiler profiler = new PrlProfiler();
        when(ruleEngine.profiler()).thenReturn(profiler);

        Map<?, ?> out = (Map<?, ?>) controller.profile("never_ran", "2.0.0", "1h").getBody();

        assertEquals(0L, out.get("executions"));
        assertEquals(0.0, out.get("avgMs"));
        assertEquals(0.0, out.get("memoryPeakKb"));
        assertEquals(List.of(), out.get("hotspots"));
        assertEquals("2.0.0", out.get("ruleVersion"));
        assertEquals("1h", out.get("window"));
    }

    @Test
    void 报告与查询参数都没有版本号时给空串而不是null() {
        PrlProfiler profiler = new PrlProfiler();
        when(ruleEngine.profiler()).thenReturn(profiler);

        Map<?, ?> out = (Map<?, ?>) controller.profile("never_ran", null, null).getBody();

        // 编辑器里 ruleVersion 是非空 string，回 null 会被拼成 "vnull" 显示在面板标题上
        assertEquals("", out.get("ruleVersion"));
    }

    private static Map<String, Object> state(String status) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("sessionId", "paused".equals(status) ? "s-1" : "");
        state.put("ruleName", RULE);
        state.put("ruleVersion", "1.0.0");
        state.put("status", status);
        state.put("currentLine", 3);
        state.put("variables", new ArrayList<>());
        state.put("callStack", new ArrayList<>());
        state.put("output", new ArrayList<>());
        state.put("isDemo", false);
        return state;
    }
}