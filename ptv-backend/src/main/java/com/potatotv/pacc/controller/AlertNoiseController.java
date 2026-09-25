package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.alert.AlertSuppressionRule;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.alert.AlertAggregationService;
import com.potatotv.pacc.service.alert.AlertCorrelationService;
import com.potatotv.pacc.service.alert.AlertSuppressionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * §4.3.2 智能告警降噪接口（受 X-Admin-Key / 管理会话保护）：
 * 降噪率统计、聚合组、误报抑制规则 CRUD。
 */
@RestController
@RequestMapping("/api/admin/alerts")
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储/流式聚合 null 分析误报
public class AlertNoiseController {

    private final AlertAggregationService aggregationService;
    private final AlertCorrelationService correlationService;
    private final AlertSuppressionService suppressionService;

    /** 降噪统计：聚合前 / 聚合后条数与降噪率（验收 A23）。 */
    @GetMapping("/noise/stats")
    @RequirePermission("alerts:read")
    public Map<String, Object> noiseStats(@RequestParam(defaultValue = "24") int windowHours) {
        return aggregationService.noiseStats(windowHours);
    }

    /** 聚合告警组分页 + 家族相关性事件。 */
    @GetMapping("/groups")
    @RequirePermission("alerts:read")
    public Map<String, Object> groups(@RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @RequestParam(defaultValue = "24") int windowHours) {
        Map<String, Object> out = new LinkedHashMap<>(aggregationService.groups(page, size));
        out.put("correlated", correlationService.correlatedEvents(windowHours));
        return out;
    }

    /** 误报抑制规则列表。 */
    @GetMapping("/suppressions")
    @RequirePermission("alerts:read")
    public List<Map<String, Object>> suppressions() {
        return suppressionService.list().stream().map(this::view).toList();
    }

    /** 新建误报抑制规则。 */
    @PostMapping("/suppressions")
    @RequirePermission("alerts:update")
    public ResponseEntity<?> createSuppression(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        try {
            AlertSuppressionRule rule = suppressionService.create(
                    str(body.get("name")), str(body.get("pattern")),
                    str(body.get("familyCode")), str(body.get("reason")), actor(request));
            return ResponseEntity.ok(view(rule));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 删除误报抑制规则。 */
    @DeleteMapping("/suppressions/{id}")
    @RequirePermission("alerts:update")
    public ResponseEntity<?> deleteSuppression(@PathVariable String id) {
        try {
            suppressionService.delete(id);
            return ResponseEntity.ok(Map.of("deleted", id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> view(AlertSuppressionRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("pattern", r.getPattern());
        m.put("familyCode", r.getFamilyCode());
        m.put("reason", r.getReason());
        m.put("enabled", r.isEnabled());
        m.put("hitCount", r.getHitCount());
        m.put("lastHitAt", r.getLastHitAt());
        m.put("createdBy", r.getCreatedBy());
        m.put("createdAt", r.getCreatedAt());
        return m;
    }

    private static String actor(HttpServletRequest request) {
        Object actor = request.getAttribute("adminActor");
        return actor == null ? "api-key" : actor.toString();
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}