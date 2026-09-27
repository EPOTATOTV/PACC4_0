package com.potatotv.pacc.controller;

import com.potatotv.pacc.rule.PrlDebugSessionService;
import com.potatotv.pacc.rule.PrlRuleEngine;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.prl.PrlException;
import com.potatotv.prl.profiler.FunctionHotspot;
import com.potatotv.prl.profiler.ProfilerReport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * PRL 在线调试与性能采样端点（设计文档 §3.3.3 / §2.15）。
 *
 * <p>与 {@link PrlAdminController} 共用 {@code /api/admin/prl} 前缀，但分成两个类：那边是一条发布
 * 状态机（草稿 → 灰度 → 发布 → 回滚），这边是「拿一份源码跑一遍」和「读一条规则的采样」两条独立
 * 路径，混在一个类里两边都读不清。安全面完全一致 —— {@code /api/admin/**} 归 {@code AdminKeyFilter}
 * 管，写操作会进 {@code AdminAuditInterceptor} 的审计。</p>
 *
 * <p>路径与响应字段严格对齐 {@code pacc-rule-language/prl-editor} 的 {@code api.ts}：调试响应一律
 * 包一层 {@code {"state": ...}}，断点表包 {@code {"breakpoints": ...}}，性能报告直接给
 * {@code RuleProfile}。编辑器用 {@code baseUrl="/api/admin/prl"} 挂载即可，一行都不用改。</p>
 */
@RestController
@RequestMapping("/api/admin/prl")
@RequiredArgsConstructor
public class PrlDebugController {

    private final PrlDebugSessionService debugSessions;
    private final PrlRuleEngine ruleEngine;

    // ------------------------------------------------------------------ 调试（§2.15.1）

    /** {@code POST /debug/session}：编译源码、开一次调试执行，返回第一个现场。 */
    @PostMapping("/debug/session")
    @RequirePermission("system:update")
    public ResponseEntity<?> createSession(@RequestBody Map<String, Object> body) {
        String source = str(body.get("source"));
        if (source.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 source"));
        }
        return withState(() -> debugSessions.create(
                str(body.get("ruleName")), source, str(body.get("ruleVersion")),
                listOfMaps(body.get("breakpoints")), stringKeyed(body.get("input"))));
    }

    /** {@code POST /debug/step}：单步，{@code mode} 为 {@code into}/{@code over}。 */
    @PostMapping("/debug/step")
    @RequirePermission("system:update")
    public ResponseEntity<?> step(@RequestBody Map<String, Object> body) {
        String sessionId = requireSessionId(body);
        if (sessionId == null) {
            return missingSessionId();
        }
        return withState(() -> debugSessions.step(sessionId, str(body.get("mode"))));
    }

    /** {@code POST /debug/resume}：放行到下一个断点。 */
    @PostMapping("/debug/resume")
    @RequirePermission("system:update")
    public ResponseEntity<?> resume(@RequestBody Map<String, Object> body) {
        return sessionCommand(body, debugSessions::resume);
    }

    /** {@code POST /debug/stop}：结束会话。 */
    @PostMapping("/debug/stop")
    @RequirePermission("system:update")
    public ResponseEntity<?> stop(@RequestBody Map<String, Object> body) {
        return sessionCommand(body, debugSessions::stop);
    }

    /** {@code PUT /debug/breakpoints}：覆盖式更新断点表。 */
    @PutMapping("/debug/breakpoints")
    @RequirePermission("system:update")
    public ResponseEntity<?> breakpoints(@RequestBody Map<String, Object> body) {
        String sessionId = requireSessionId(body);
        if (sessionId == null) {
            return missingSessionId();
        }
        try {
            return ResponseEntity.ok(Map.of("breakpoints",
                    debugSessions.updateBreakpoints(sessionId, listOfMaps(body.get("breakpoints")))));
        } catch (PrlException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 性能（§2.15.2）

    /**
     * {@code GET /profiles/{ruleName}?version=&window=}：一条规则的性能报告。
     *
     * <p>规则没跑过也返回 200 + 全零，而不是 404：面板要能区分「这条规则没数据」和「接口挂了」，
     * 后者才是错误。</p>
     */
    @GetMapping("/profiles/{ruleName}")
    @RequirePermission("system:read")
    public ResponseEntity<?> profile(@PathVariable String ruleName,
                                     @RequestParam(required = false) String version,
                                     @RequestParam(required = false) String window) {
        return ResponseEntity.ok(profileJson(ruleEngine.profiler().report(ruleName), version, window));
    }

    // ------------------------------------------------------------------ 序列化

    /**
     * 采样报告 → 编辑器 {@code RuleProfile}。
     *
     * <p>版本号优先用采样时登记的：调用方传的 {@code version} 只是「想看的版本」，而报告里的版本才是
     * 真正跑出这些数字的那个；没登记过才退回调用方传的值。两边都没有时给空串，不能给 {@code null} ——
     * 编辑器里 {@code ruleVersion} 是非空 {@code string}，面板会把它渲染成 {@code vnull}。</p>
     */
    private static Map<String, Object> profileJson(ProfilerReport report, String requestedVersion,
                                                   String window) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ruleName", report.ruleName());
        out.put("ruleVersion", ruleVersion(report, requestedVersion));
        out.put("executions", report.executions());
        out.put("avgMs", millis(report.averageNanos()));
        out.put("p95Ms", millis(report.p95Nanos()));
        out.put("p99Ms", millis(report.p99Nanos()));
        out.put("maxMs", millis(report.maxNanos()));
        out.put("memoryPeakKb", report.memoryPeakBytes() / 1024.0);
        out.put("hotspots", hotspotsJson(report));
        out.put("suggestions", report.suggestions());
        out.put("window", window == null || window.isBlank() ? "进程启动至今" : window);
        return out;
    }

    private static List<Map<String, Object>> hotspotsJson(ProfilerReport report) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FunctionHotspot hotspot : report.hotspots()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", hotspot.name());
            row.put("percent", Math.round(hotspot.share() * 1000) / 10.0);
            row.put("totalMs", millis(hotspot.selfNanos()));
            // 库给的优化建议是整段的自然语言，只有提到某个函数名的才能挂到那一行上；
            // 剩下的是规则级建议，面板会在下面的「优化建议」里整段列出来。
            suggestionFor(report.suggestions(), hotspot.name()).ifPresent(text -> row.put("suggestion", text));
            out.add(row);
        }
        return out;
    }

    private static Optional<String> suggestionFor(List<String> suggestions, String hotspotName) {
        return suggestions.stream().filter(text -> text.contains(hotspotName)).findFirst();
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }

    /** 报告里登记的版本 → 调用方要看的版本 → 空串。 */
    private static String ruleVersion(ProfilerReport report, String requestedVersion) {
        if (report.version() != null) {
            return report.version();
        }
        return requestedVersion == null ? "" : requestedVersion;
    }

    // ------------------------------------------------------------------ 请求解析

    private static ResponseEntity<?> sessionCommand(Map<String, Object> body,
                                                    Function<String, Map<String, Object>> action) {
        String sessionId = requireSessionId(body);
        if (sessionId == null) {
            return missingSessionId();
        }
        return withState(() -> action.apply(sessionId));
    }

    /** 调试动作的共同外壳：服务层的失败如实转成 400，成功包一层 {@code {"state": ...}}。 */
    private static ResponseEntity<?> withState(Supplier<Map<String, Object>> action) {
        try {
            return ResponseEntity.ok(Map.of("state", action.get()));
        } catch (PrlException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String requireSessionId(Map<String, Object> body) {
        String sessionId = str(body.get("sessionId"));
        return sessionId.isBlank() ? null : sessionId;
    }

    private static ResponseEntity<?> missingSessionId() {
        return ResponseEntity.badRequest().body(Map.of("error", "缺少 sessionId"));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static List<Map<String, Object>> listOfMaps(Object value) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                out.add(stringKeyed(item));
            }
        }
        return out;
    }

    private static Map<String, Object> stringKeyed(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return out;
    }
}