package com.potatotv.pacc.controller;

import com.potatotv.pacc.rule.PrlRuleReleaseService;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.prl.Prl;
import com.potatotv.prl.PrlException;
import com.potatotv.prl.analysis.AnalysisResult;
import com.potatotv.prl.analysis.PrlAnalyzer;
import com.potatotv.prl.analysis.RuleConflict;
import com.potatotv.prl.analysis.RuleMetrics;
import com.potatotv.prl.check.Diagnostic;
import com.potatotv.prl.engine.DetectionResult;
import com.potatotv.prl.engine.RuleVersion;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PRL 规则管理端点（设计文档 §3.3.2）：线编写 → 静态分析 → 草稿 → 灰度 → 审批 → 回滚。
 *
 * <p><b>为什么挂在 {@code /api/admin/prl} 而不是文档里写的 {@code /api/v1/admin/rules}。</b>
 * {@code /api/v1/**} 在安全配置里归 {@code ApiV1AuthFilter} 管，那是给第三方集成用的开放 API 面，
 * 要求调用方持有 API Key 并做 HMAC 签名 —— 浏览器里跑的管理端既不该也不能这么调用。管理面
 * 统一走 {@code /api/admin/**}，才能零改动地吃到 {@code AdminKeyFilter}（登录态 + Origin 校验）、
 * {@code AdminAuditInterceptor}（写操作审计）和 {@code PermissionInterceptor}（模块×操作）。</p>
 *
 * <p>端点集合与字段名严格对齐 {@code pacc-rule-language/prl-editor} 里的 {@code api.ts}：
 * 那个包已经按 {@code /api/prl/...} 写好了前端调用，管理端挂载时用
 * {@code baseUrl="/api/admin/prl"} 注入即可，前端不需要为路径差异改一行代码。</p>
 */
@RestController
@RequestMapping("/api/admin/prl")
@RequiredArgsConstructor
public class PrlAdminController {

    private final PrlRuleReleaseService releaseService;

    // ------------------------------------------------------------------ 静态分析

    /** {@code POST /analyze}：编辑器保存前的服务端分析，诊断 + 复杂度 + 引擎版本。 */
    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, Object> body) {
        String source = str(body.get("source"));
        if (source.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 source"));
        }
        String ruleName = str(body.get("ruleName"));
        AnalysisResult result = releaseService.analyze(source);

        List<Map<String, Object>> diagnostics = new ArrayList<>();
        for (Diagnostic diagnostic : result.diagnostics()) {
            diagnostics.add(diagnosticJson(diagnostic));
        }
        // 冲突不进 Diagnostic：它只配报建议，不阻断发布。混在错误里会让编辑器把可发布的规则标红。
        for (RuleConflict conflict : result.conflicts()) {
            diagnostics.add(conflictJson(conflict));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("diagnostics", diagnostics);
        complexityJson(result, ruleName).ifPresent(c -> out.put("complexity", c));
        out.put("engineVersion", Prl.VERSION);
        out.put("report", result.report());
        return ResponseEntity.ok(out);
    }

    // ------------------------------------------------------------------ 版本管理

    /** {@code GET /versions?ruleName=}：不带规则名时给出全库版本。 */
    @GetMapping("/versions")
    @RequirePermission("system:read")
    public ResponseEntity<?> versions(@RequestParam(required = false) String ruleName) {
        List<Map<String, Object>> versions = new ArrayList<>();
        for (RuleVersion version : releaseService.versions(ruleName)) {
            versions.add(versionJson(version));
        }
        return ResponseEntity.ok(Map.of("versions", versions));
    }

    /** {@code POST /versions}：提交草稿；静态分析不通过就不入库。 */
    @PostMapping("/versions")
    @RequirePermission("system:update")
    public ResponseEntity<?> createDraft(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String ruleName = str(body.get("ruleName"));
        String version = str(body.get("version"));
        String source = str(body.get("source"));
        if (ruleName.isBlank() || version.isBlank() || source.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ruleName / version / source 都不能为空"));
        }
        String author = firstNonBlank(str(body.get("author")), actor(req));
        try {
            return ResponseEntity.ok(versionJson(
                    releaseService.submitDraft(ruleName, version, source, author)));
        } catch (PrlException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** {@code POST /versions/{ruleName}/{version}/canary}：进入灰度并写下放量比例。 */
    @PostMapping("/versions/{ruleName}/{version}/canary")
    @RequirePermission("system:update")
    public ResponseEntity<?> startCanary(@PathVariable String ruleName, @PathVariable String version,
                                         @RequestBody(required = false) Map<String, Object> body) {
        Integer percent = intOrNull(body == null ? null : body.get("playerPercent"));
        try {
            return ResponseEntity.ok(versionJson(releaseService.startCanary(ruleName, version, percent)));
        } catch (PrlException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** {@code POST /versions/{ruleName}/{version}/approve}：审批发布为 active，同时回到全量。 */
    @PostMapping("/versions/{ruleName}/{version}/approve")
    @RequirePermission("system:update")
    public ResponseEntity<?> approve(@PathVariable String ruleName, @PathVariable String version,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest req) {
        // 审批人优先取请求体，管理端没填就用当前登录身份 —— 生产发布必须有审批人，不留空口子。
        String approver = firstNonBlank(str(body == null ? null : body.get("approvedBy")), actor(req));
        try {
            return ResponseEntity.ok(versionJson(releaseService.approve(ruleName, version, approver)));
        } catch (PrlException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** {@code POST /versions/{ruleName}/rollback}：回滚到当前生效版本记录的目标版本。 */
    @PostMapping("/versions/{ruleName}/rollback")
    @RequirePermission("system:update")
    public ResponseEntity<?> rollback(@PathVariable String ruleName,
                                      @RequestBody(required = false) Map<String, Object> body) {
        String target = body == null ? null : str(body.get("targetVersion"));
        try {
            return ResponseEntity.ok(versionJson(releaseService.rollback(ruleName, target)));
        } catch (PrlException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 灰度与试跑

    /** {@code GET /rollout}：各规则当前放量比例，供管理端显示灰度进度。 */
    @GetMapping("/rollout")
    @RequirePermission("system:read")
    public ResponseEntity<?> rollout() {
        return ResponseEntity.ok(Map.of("percents", releaseService.rolloutPercents()));
    }

    /** {@code POST /test}：拿一份样本输入试跑某个已入库版本，回答「这条规则到底会不会报」。 */
    @PostMapping("/test")
    @RequirePermission("system:update")
    public ResponseEntity<?> test(@RequestBody Map<String, Object> body) {
        String ruleName = str(body.get("ruleName"));
        String version = str(body.get("version"));
        if (ruleName.isBlank() || version.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "ruleName / version 都不能为空"));
        }
        Map<String, Object> input = body.get("input") instanceof Map<?, ?> raw ? toStringKeyed(raw) : Map.of();
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            var hit = releaseService.testRun(ruleName, version, input);
            out.put("hit", hit.isPresent());
            hit.map(DetectionResult::toMap).ifPresent(result -> out.put("result", result));
            return ResponseEntity.ok(out);
        } catch (PrlException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ 序列化

    /** 编辑器 {@code RuleVersion} 的字段名与类型（{@code status} 小写、{@code createdAt} ISO-8601）。 */
    private static Map<String, Object> versionJson(RuleVersion version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", version.ruleName() + "@" + version.version());
        m.put("ruleName", version.ruleName());
        m.put("version", version.version());
        m.put("status", version.status().name().toLowerCase(Locale.ROOT));
        m.put("author", version.author());
        m.put("approvedBy", version.approvedBy());
        m.put("createdAt", Instant.ofEpochMilli(version.createdAtMs()).toString());
        m.put("checksum", version.checksum());
        m.put("rollbackTo", version.rollbackTo());
        return m;
    }

    private static Map<String, Object> diagnosticJson(Diagnostic diagnostic) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", diagnostic.line());
        m.put("column", diagnostic.col());
        m.put("severity", diagnostic.isError() ? "error" : "warning");
        m.put("code", diagnostic.code());
        m.put("message", diagnostic.message());
        return m;
    }

    /** 冲突是文件级的，没有行列，按第 0 行呈现；{@code hint} 放处理建议。 */
    private static Map<String, Object> conflictJson(RuleConflict conflict) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("line", 0);
        m.put("column", 0);
        m.put("severity", "warning");
        m.put("code", PrlAnalyzer.CONFLICT_CODE);
        m.put("message", conflict.reason() + "（" + conflict.ruleA() + " / " + conflict.ruleB() + "）");
        m.put("hint", conflict.suggestion());
        return m;
    }

    /**
     * 复杂度摘要：优先取入参规则名对应的那条；编辑器一次编辑一条规则，取不到就退回第一条。
     *
     * <p>{@code score} 按编辑器约定是字符串。</p>
     */
    private static java.util.Optional<Map<String, Object>> complexityJson(AnalysisResult result, String ruleName) {
        RuleMetrics metrics = result.metrics(ruleName)
                .or(() -> result.metrics().stream().findFirst())
                .orElse(null);
        if (metrics == null) {
            return java.util.Optional.empty();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cyclomaticComplexity", metrics.cyclomaticComplexity());
        m.put("maxNestingDepth", metrics.maxNestingDepth());
        m.put("estimatedCostUs", metrics.estimatedNanos() / 1000);
        m.put("estimatedMemoryKb", metrics.estimatedHeapBytes() / 1024);
        m.put("score", String.valueOf(metrics.complexityScore()));
        return java.util.Optional.of(m);
    }

    private static Map<String, Object> toStringKeyed(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        raw.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    private static String actor(HttpServletRequest req) {
        Object value = req.getAttribute("adminActor");
        return value == null || value.toString().isBlank() ? "api-key" : value.toString();
    }

    private static String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static Integer intOrNull(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}