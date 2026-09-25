package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.automation.AutomationExecution;
import com.potatotv.pacc.domain.automation.AutomationRule;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.automation.AutomationEvaluator;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §4.3.3 自动化响应接口（受 X-Admin-Key / 管理会话保护）：
 * 规则列表、启停切换、执行历史与动作回滚。
 */
@RestController
@RequestMapping("/api/admin/automation")
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储 null 分析误报
public class AutomationController {

    private final AutomationEvaluator evaluator;

    /** 自动化规则列表。 */
    @GetMapping("/rules")
    @RequirePermission("system:read")
    public List<Map<String, Object>> rules() {
        return evaluator.listRules().stream().map(this::ruleView).toList();
    }

    /** 启用 / 停用规则（停用即 no-op）。 */
    @PostMapping("/rules/{code}/toggle")
    @RequirePermission("system:update")
    public ResponseEntity<?> toggle(@PathVariable String code, @RequestBody Map<String, Object> body) {
        Object enabled = body == null ? null : body.get("enabled");
        if (!(enabled instanceof Boolean flag)) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少布尔字段 enabled"));
        }
        try {
            return ResponseEntity.ok(ruleView(evaluator.toggle(code, flag)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    /** 执行历史分页。 */
    @GetMapping("/executions")
    @RequirePermission("system:read")
    public Map<String, Object> executions(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return evaluator.executions(page, size);
    }

    /** 回滚一次已生效的自动动作。 */
    @PostMapping("/executions/{id}/revert")
    @RequirePermission("system:update")
    public ResponseEntity<?> revert(@PathVariable Long id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(executionView(evaluator.revert(id, actor(request))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> ruleView(AutomationRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode());
        m.put("name", r.getName());
        m.put("trigger", r.getTriggerExpr());
        m.put("action", r.getActionCode());
        m.put("enabled", r.isEnabled());
        m.put("threshold", r.getThreshold());
        m.put("windowMin", r.getWindowMin());
        m.put("cooldownMin", r.getCooldownMin());
        m.put("builtin", r.isBuiltin());
        m.put("lastFiredAt", r.getLastFiredAt());
        m.put("updatedAt", r.getUpdatedAt());
        return m;
    }

    private Map<String, Object> executionView(AutomationExecution e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ruleCode", e.getRuleCode());
        m.put("actionCode", e.getActionCode());
        m.put("status", e.getStatus());
        m.put("detail", e.getDetail());
        m.put("reversible", e.isReversible());
        m.put("reverted", e.isReverted());
        m.put("revertDetail", e.getRevertDetail());
        m.put("executedAt", e.getExecutedAt());
        m.put("executedBy", e.getExecutedBy());
        return m;
    }

    private static String actor(HttpServletRequest request) {
        Object actor = request.getAttribute("adminActor");
        return actor == null ? "api-key" : actor.toString();
    }
}