package com.potatotv.pacc.controller;

import com.potatotv.pacc.rule.PrlRuleReleaseService;
import com.potatotv.prl.Prl;
import com.potatotv.prl.PrlException;
import com.potatotv.prl.analysis.AnalysisResult;
import com.potatotv.prl.analysis.RuleConflict;
import com.potatotv.prl.analysis.RuleMetrics;
import com.potatotv.prl.check.Diagnostic;
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.engine.RuleStatus;
import com.potatotv.prl.engine.RuleVersion;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 管理端规则管理端点（设计文档 §3.3.2）的响应契约。
 *
 * <p>这一组钉的是<strong>字段名与类型</strong>，不是业务逻辑：服务端的规则生命周期已经由
 * {@code PrlRuleReleaseServiceTest} 用真库真引擎覆盖过了，这里要防的是另一种事故 ——
 * 后端把 {@code status} 写成 {@code ACTIVE}、把 {@code createdAt} 写成毫秒数、
 * 把冲突当成错误塞进诊断列表，前端与编辑器都只会在运行时才发现，而且表现是「页面空白」这种
 * 最难查的形态。</p>
 */
class PrlAdminControllerTest {

    private static final String RULE = "account_history";

    private final PrlRuleReleaseService service = mock(PrlRuleReleaseService.class);
    private final PrlAdminController controller = new PrlAdminController(service);

    private static HttpServletRequest actor(String name) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("adminActor")).thenReturn(name);
        return req;
    }

    // ------------------------------------------------------------------ analyze

    @Test
    void 分析结果按编辑器契约给出诊断与复杂度() {
        AnalysisResult result = new AnalysisResult(null,
                List.of(Diagnostic.error("PRL-T01", "类型不匹配", 3, 7),
                        Diagnostic.warning("PRL-W02", "未使用变量", 5, 1)),
                List.of(new RuleMetrics(RULE, 4, 2, 30, 300L, 2048L, 12)),
                List.of(new RuleConflict(RULE, "combat_assist", "severity >= 'low'", "结论可能矛盾", "收紧条件")));
        when(service.analyze(any())).thenReturn(result);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleName", RULE);
        body.put("source", "rule \"account_history\" { }");
        ResponseEntity<?> resp = controller.analyze(body);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<?, ?> out = (Map<?, ?>) resp.getBody();
        assertEquals(Prl.VERSION, out.get("engineVersion"));

        List<?> diagnostics = (List<?>) out.get("diagnostics");
        assertEquals(3, diagnostics.size(), "两条诊断 + 一条冲突");

        Map<?, ?> error = (Map<?, ?>) diagnostics.get(0);
        assertEquals(3, error.get("line"));
        assertEquals(7, error.get("column"));
        assertEquals("error", error.get("severity"));
        assertEquals("PRL-T01", error.get("code"));
        assertEquals("类型不匹配", error.get("message"));

        assertEquals("warning", ((Map<?, ?>) diagnostics.get(1)).get("severity"));

        // 冲突按「第 0 行 + warning」呈现：它是建议，不能把可发布的规则标红
        Map<?, ?> conflict = (Map<?, ?>) diagnostics.get(2);
        assertEquals(0, conflict.get("line"));
        assertEquals(0, conflict.get("column"));
        assertEquals("warning", conflict.get("severity"));
        assertEquals("PRL-C", conflict.get("code"));
        assertTrue(String.valueOf(conflict.get("message")).contains("combat_assist"));
        assertEquals("收紧条件", conflict.get("hint"));

        Map<?, ?> complexity = (Map<?, ?>) out.get("complexity");
        assertEquals(4, complexity.get("cyclomaticComplexity"));
        assertEquals(2, complexity.get("maxNestingDepth"));
        assertEquals(0L, complexity.get("estimatedCostUs"), "300ns 按整数除应得 0µs");
        assertEquals(2L, complexity.get("estimatedMemoryKb"));
        assertEquals("12", complexity.get("score"), "score 按编辑器约定是字符串");
    }

    @Test
    void 分析缺少源码直接拒绝() {
        ResponseEntity<?> resp = controller.analyze(new LinkedHashMap<>(Map.of("ruleName", RULE)));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).analyze(any());
    }

    // ------------------------------------------------------------------ 版本序列化

    @Test
    void 版本列表的字段名与状态大小写符合编辑器契约() {
        RuleVersion version = new RuleVersion(RULE, "2.0.0", "source", new byte[]{1, 2},
                "tester", RuleStatus.DRAFT, 1_700_000_000_000L);
        when(service.versions(null)).thenReturn(List.of(version));

        Map<?, ?> out = (Map<?, ?>) controller.versions(null).getBody();
        Map<?, ?> one = (Map<?, ?>) ((List<?>) out.get("versions")).get(0);

        assertEquals(RULE + "@2.0.0", one.get("id"));
        assertEquals(RULE, one.get("ruleName"));
        assertEquals("2.0.0", one.get("version"));
        assertEquals("draft", one.get("status"), "status 必须是编辑器认的小写形式");
        assertEquals("tester", one.get("author"));
        assertEquals(version.checksum(), one.get("checksum"));
        assertEquals("2023-11-14T22:13:20Z", one.get("createdAt"), "createdAt 必须是 ISO-8601 字符串");
        assertNull(one.get("approvedBy"));
        assertNull(one.get("rollbackTo"));
    }

    @Test
    void 提交草稿时作者回退到登录身份且分析失败返回四百() {
        when(service.submitDraft(eq(RULE), eq("1.0.0"), any(), eq("alice")))
                .thenThrow(new PrlException("静态分析未通过"));
        when(service.submitDraft(eq(RULE), eq("1.1.0"), any(), eq("alice")))
                .thenReturn(new RuleVersion(RULE, "1.1.0", "s", new byte[0], "alice",
                        RuleStatus.DRAFT, 1L));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleName", RULE);
        body.put("version", "1.0.0");
        body.put("source", "bad");

        ResponseEntity<?> failed = controller.createDraft(body, actor("alice"));
        assertEquals(HttpStatus.BAD_REQUEST, failed.getStatusCode());
        assertTrue(String.valueOf(((Map<?, ?>) failed.getBody()).get("error"))
                .contains("静态分析未通过"), "分析报告要原样回给编辑器");

        body.put("version", "1.1.0");
        assertEquals(HttpStatus.OK, controller.createDraft(body, actor("alice")).getStatusCode());
    }

    @Test
    void 提交草稿缺少必填字段直接拒绝() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleName", RULE);
        body.put("source", "s");

        ResponseEntity<?> resp = controller.createDraft(body, actor("alice"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).submitDraft(any(), any(), any(), any());
    }

    // ------------------------------------------------------------------ 灰度 / 发布 / 回滚

    @Test
    void 灰度比例缺失时交给服务端默认值() {
        when(service.startCanary(eq(RULE), eq("1.0.0"), any()))
                .thenReturn(new RuleVersion(RULE, "1.0.0", "s", new byte[0], "alice",
                        RuleStatus.TESTING, 1L));

        controller.startCanary(RULE, "1.0.0", null);
        verify(service).startCanary(RULE, "1.0.0", null);

        controller.startCanary(RULE, "1.0.0", Map.of("playerPercent", "25"));
        verify(service).startCanary(RULE, "1.0.0", 25);
    }

    @Test
    void 审批人回退到登录身份且为空时用兜底名() {
        when(service.approve(eq(RULE), eq("1.0.0"), any())).thenReturn(
                new RuleVersion(RULE, "1.0.0", "s", new byte[0], "alice", RuleStatus.ACTIVE, 1L));

        controller.approve(RULE, "1.0.0", Map.of("approvedBy", "   "), actor("alice"));
        verify(service).approve(RULE, "1.0.0", "alice");

        HttpServletRequest anonymous = mock(HttpServletRequest.class);
        controller.approve(RULE, "1.0.0", null, anonymous);
        verify(service).approve(RULE, "1.0.0", "api-key");
    }

    @Test
    void 回滚把目标版本透传并如实报错() {
        when(service.rollback(eq(RULE), any())).thenThrow(new PrlException("不能回滚到 v9.9.9"));

        ResponseEntity<?> resp = controller.rollback(RULE, Map.of("targetVersion", "9.9.9"));

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service).rollback(RULE, "9.9.9");
        assertTrue(String.valueOf(((Map<?, ?>) resp.getBody()).get("error")).contains("v9.9.9"));
    }

    @Test
    void 放量比例查询把未登记的规则补成全量() {
        when(service.rolloutPercents()).thenReturn(Map.of(RULE, 100, "combat_assist", 10));

        Map<?, ?> out = (Map<?, ?>) controller.rollout().getBody();

        assertEquals(Map.of(RULE, 100, "combat_assist", 10), out.get("percents"));
    }

    // ------------------------------------------------------------------ 试跑

    @Test
    void 试跑命中时返回明细未命中时只给布尔() {
        DetectionResult hit = new DetectionResult(RULE, "account_history", 0.42,
                Map.of("score", 2.4), 1_700_000_000_000L);
        when(service.testRun(eq(RULE), eq("1.0.0"), any())).thenReturn(Optional.of(hit));
        when(service.testRun(eq(RULE), eq("2.0.0"), any())).thenReturn(Optional.empty());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleName", RULE);
        body.put("version", "1.0.0");
        body.put("input", Map.of("reputation", 70.0));

        Map<?, ?> out = (Map<?, ?>) controller.test(body).getBody();
        assertEquals(true, out.get("hit"));
        Map<?, ?> result = (Map<?, ?>) out.get("result");
        assertEquals(RULE, result.get("rule_name"));
        assertEquals("account_history", result.get("type"));

        body.put("version", "2.0.0");
        Map<?, ?> miss = (Map<?, ?>) controller.test(body).getBody();
        assertEquals(false, miss.get("hit"));
        assertFalse(miss.containsKey("result"), "未命中时不该给空的明细字段");
    }

    @Test
    void 试跑缺少规则名或版本时拒绝() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ruleName", RULE);

        ResponseEntity<?> resp = controller.test(body);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verify(service, never()).testRun(any(), any(), any());
    }

    @Test
    void 分析无复杂度数据时不下发空的complexity字段() {
        when(service.analyze(any())).thenReturn(new AnalysisResult(null, List.of(), List.of(), List.of()));

        Map<?, ?> out = (Map<?, ?>) controller.analyze(new LinkedHashMap<>(Map.of("source", "rule {}")))
                .getBody();

        assertFalse(out.containsKey("complexity"));
        assertTrue(((List<?>) out.get("diagnostics")).isEmpty());
    }

    /** 只有 {@link PrlException} 算「用户写错了」，按 400 回；其余异常照常抛给全局处理器按 500 处理，不能吞。 */
    @Test
    void 非Prl异常原样抛出交给全局处理器() {
        when(service.analyze(any())).thenThrow(new IllegalStateException("boom"));

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> controller.analyze(new LinkedHashMap<>(Map.of("source", "x"))));
        assertEquals("boom", thrown.getMessage());
    }
}