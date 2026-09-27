package com.potatotv.pacc.controller;

import com.potatotv.pacc.rule.PrlRuleReleaseService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 玩家端规则下发端点（设计文档 §3.2.4）：清单 + 源码下载。
 *
 * <p>这一组钉两件事。一是<strong>鉴权不能靠过滤器兜底</strong>：过滤器只管请求进不进得来，
 * 端点自己仍要判空 PTEID，否则过滤器链路一改就是「未登录也能拉规则」。
 * 二是<strong>响应头不能成为注入面</strong>：版本号来自管理端输入，目前没有任何格式校验，
 * 一个带 CRLF 的版本号就能往响应里插头。</p>
 */
class PrlRuleDistributionControllerTest {

    private static final String RULE = "fastplace";
    private static final String SHA = "a".repeat(64);

    private final PrlRuleReleaseService service = mock(PrlRuleReleaseService.class);
    private final PrlRuleDistributionController controller = new PrlRuleDistributionController(service);

    private static HttpServletRequest player(String pteid) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute("pteid")).thenReturn(pteid);
        return req;
    }

    private static PrlRuleReleaseService.RuleDelivery delivery(String name, String version, String status) {
        return new PrlRuleReleaseService.RuleDelivery(name, version, SHA, status, "rule \"" + name + "\" { }");
    }

    // ------------------------------------------------------------------ 鉴权

    @Test
    void 无玩家身份时清单与下载都拒绝() {
        assertEquals(HttpStatus.UNAUTHORIZED, controller.manifest(player(null)).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.manifest(player("")).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, controller.file(RULE, player(null)).getStatusCode());

        verify(service, never()).deliveriesFor(anyString());
    }

    // ------------------------------------------------------------------ 清单

    @Test
    void 清单给出名称版本摘要与下载地址() {
        when(service.deliveriesFor("PTE001")).thenReturn(List.of(delivery(RULE, "1.2.0", "active")));

        Map<?, ?> out = (Map<?, ?>) controller.manifest(player("PTE001")).getBody();
        Map<?, ?> one = (Map<?, ?>) ((List<?>) out.get("rules")).get(0);

        assertEquals(RULE, one.get("name"));
        assertEquals("1.2.0", one.get("version"));
        assertEquals(SHA, one.get("sha256"));
        assertEquals("active", one.get("status"));
        assertEquals("/api/player/rules/" + RULE + "/file", one.get("url"));
    }

    /** 清单为空是合法结果（设备不在灰度内且没有基线版本），不能报错。 */
    @Test
    void 没有可下发规则时返回空清单() {
        when(service.deliveriesFor(anyString())).thenReturn(List.of());

        Map<?, ?> out = (Map<?, ?>) controller.manifest(player("PTE001")).getBody();

        assertTrue(((List<?>) out.get("rules")).isEmpty());
    }

    // ------------------------------------------------------------------ 下载

    @Test
    void 下载返回源码与可核对的响应头() {
        when(service.deliveriesFor("PTE001")).thenReturn(List.of(delivery(RULE, "1.2.0", "active")));

        ResponseEntity<?> resp = controller.file(RULE, player("PTE001"));

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("rule \"" + RULE + "\" { }", resp.getBody());
        assertEquals("text/plain;charset=UTF-8", resp.getHeaders().getContentType().toString());
        assertEquals(RULE, resp.getHeaders().getFirst("X-PACC-Rule-Name"));
        assertEquals("1.2.0", resp.getHeaders().getFirst("X-PACC-Rule-Version"));
        assertEquals(SHA, resp.getHeaders().getFirst("X-PACC-Rule-SHA256"));
        assertEquals("no-store", resp.getHeaders().getCacheControl());
    }

    /** 不在灰度内的设备拿不到源码：统一 404，不让端侧去猜「是不是被灰度排除了」。 */
    @Test
    void 设备不在灰度内时下载返回404() {
        when(service.deliveriesFor("PTE002")).thenReturn(List.of(delivery("combat_assist", "1.0.0", "active")));

        ResponseEntity<?> resp = controller.file(RULE, player("PTE002"));

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }

    @Test
    void 版本号里的换行与引号被过滤掉() {
        String evil = "1.0.0\r\nX-Injected: 1";
        when(service.deliveriesFor("PTE001")).thenReturn(List.of(delivery(RULE, evil, "active")));

        ResponseEntity<?> resp = controller.file(RULE, player("PTE001"));

        assertEquals("1.0.0__X-Injected__1", resp.getHeaders().getFirst("X-PACC-Rule-Version"));
        assertNull(resp.getHeaders().getFirst("X-Injected"), "CRLF 不得拼出新头");
    }

    @Test
    void 超长版本号被截断到六十四字符() {
        String long64 = "9".repeat(40) + "." + "8".repeat(60);
        when(service.deliveriesFor("PTE001")).thenReturn(List.of(delivery(RULE, long64, "active")));

        ResponseEntity<?> resp = controller.file(RULE, player("PTE001"));

        assertEquals(64, resp.getHeaders().getFirst("X-PACC-Rule-Version").length());
    }
}