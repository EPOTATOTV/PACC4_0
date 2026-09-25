package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.tenant.TenantDetectionRecord;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.tenant.TenantDataService;
import com.potatotv.pacc.service.tenant.TenantQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * §4.2.3 多租户配额 / 计量 / 租户隔离数据接口（受 X-Admin-Key / 管理会话保护，
 * 并由 {@code TenantContextFilter} 解析租户上下文）。
 *
 * <p>与既有 {@code TenantController}（{@code /api/admin/tenant}）协同：本控制器只新增
 * {@code quota} / {@code usage} / {@code detections} 子路径，不移除或改名任何既有端点。</p>
 */
@RestController
@RequestMapping("/api/admin/tenant")
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储/流式聚合 null 分析误报
public class TenantOpsController {

    private final TenantQuotaService quotaService;
    private final TenantDataService dataService;

    /** 每租户配额 + 当前用量（玩家数 / 检测量 / 存储）。 */
    @GetMapping("/quota")
    @RequirePermission("tenant:read")
    public ResponseEntity<?> quota() {
        return safe(() -> quotaService.quotaView());
    }

    /** 计费计量流水（?tenantId= 指定；缺省取当前生效租户）。 */
    @GetMapping("/usage")
    @RequirePermission("tenant:read")
    public ResponseEntity<?> usage(@RequestParam(required = false) String tenantId) {
        return safe(() -> quotaService.usage(tenantId));
    }

    /** 当前租户的检测记录分页（强制租户过滤）。 */
    @GetMapping("/detections")
    @RequirePermission("tenant:read")
    public ResponseEntity<?> detections(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return safe(() -> dataService.list(page, size));
    }

    /** 写入一条租户检测记录（配额校验 + 计量）。 */
    @PostMapping("/detections")
    @RequirePermission("tenant:update")
    public ResponseEntity<?> recordDetection(@RequestBody Map<String, Object> body) {
        return safe(() -> {
            long bytes = body.get("storageBytes") instanceof Number n ? n.longValue() : 0L;
            TenantDetectionRecord r = dataService.record(
                    String.valueOf(body.getOrDefault("pteid", "")),
                    String.valueOf(body.getOrDefault("eventType", "unknown")),
                    String.valueOf(body.getOrDefault("severity", "low")),
                    body.get("riskScore") instanceof Number n ? n.intValue() : 0,
                    bytes);
            return Map.of("id", r.getId(), "tenantId", r.getTenantId(), "occurredAt", r.getOccurredAt());
        });
    }

    /** 读取单条租户检测记录（跨租户即拒绝）。 */
    @GetMapping("/detections/{id}")
    @RequirePermission("tenant:read")
    public ResponseEntity<?> detection(@PathVariable String id) {
        return safe(() -> dataService.get(id));
    }

    private ResponseEntity<?> safe(Supplier s) {
        try {
            return ResponseEntity.ok(s.get());
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @FunctionalInterface
    private interface Supplier {
        Object get();
    }
}