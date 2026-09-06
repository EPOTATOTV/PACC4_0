package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.ApiKey;
import com.potatotv.pacc.domain.ApiUsageLog;
import com.potatotv.pacc.repository.ApiKeyRepository;
import com.potatotv.pacc.repository.ApiUsageLogRepository;
import com.potatotv.pacc.service.ApiKeyService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
 * v4.8 开放 API 密钥管理（管理端）：创建/轮换/禁用/删除 + Webhook 配置 + 调用审计查询。
 * 密钥明文仅在创建/轮换响应中返回一次。
 */
@RestController
@RequestMapping("/api/admin/api")
@RequiredArgsConstructor
public class ApiKeyAdminController {

    private final ApiKeyService keyService;
    private final ApiKeyRepository keyRepository;
    private final ApiUsageLogRepository usageLogRepository;

    @GetMapping("/keys")
    public ResponseEntity<List<Map<String, Object>>> list(@RequestParam(defaultValue = "platform") String tenant) {
        return ResponseEntity.ok(keyService.list(tenant).stream().map(this::view).toList());
    }

    @PostMapping("/keys")
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        ApiKeyService.CreatedKey c = keyService.create(
                str(body.get("name")),
                str(body.get("tenantId")),
                str(body.get("plan")),
                str(body.get("scopes")),
                nullable(body.get("categories")),
                nullable(body.get("ipWhitelist")),
                body.get("rateLimit") instanceof Number n ? n.intValue() : null,
                nullable(body.get("webhookUrl")),
                nullable(body.get("webhookSecret")),
                "admin");
        Map<String, Object> out = view(c.key());
        out.put("secret", c.secret()); // 一次性明文
        return ResponseEntity.status(201).body(out);
    }

    @PostMapping("/keys/{id}/rotate")
    public ResponseEntity<Map<String, Object>> rotate(@PathVariable String id) {
        ApiKeyService.CreatedKey c = keyService.rotate(id);
        Map<String, Object> out = view(c.key());
        out.put("secret", c.secret());
        return ResponseEntity.ok(out);
    }

    @PostMapping("/keys/{id}/toggle")
    public ResponseEntity<Map<String, Object>> toggle(@PathVariable String id,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        boolean enabled = body == null || !Boolean.FALSE.equals(body.get("enabled"));
        keyService.setEnabled(id, enabled);
        return ResponseEntity.ok(Map.of("enabled", enabled));
    }

    @DeleteMapping("/keys/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        keyService.delete(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }

    @PostMapping("/keys/{id}/webhook")
    public ResponseEntity<Map<String, Object>> webhook(@PathVariable String id, @RequestBody Map<String, Object> body) {
        keyService.updateWebhook(id, nullable(body.get("url")), nullable(body.get("secret")));
        return ResponseEntity.ok(Map.of("updated", true));
    }

    @GetMapping("/audit")
    public ResponseEntity<Map<String, Object>> audit(
            @RequestParam(required = false) String keyId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var pg = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        var src = (keyId == null || keyId.isBlank())
                ? usageLogRepository.findAllByOrderByCreatedAtDesc(pg)
                : usageLogRepository.findByApiKeyIdOrderByCreatedAtDesc(keyId, pg);
        List<Map<String, Object>> rows = src.getContent().stream().map(l -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", l.getId());
            m.put("apiKeyId", l.getApiKeyId());
            m.put("tenantId", l.getTenantId());
            m.put("method", l.getMethod());
            m.put("path", l.getPath());
            m.put("ip", l.getIp());
            m.put("statusCode", l.getStatusCode());
            m.put("latencyMs", l.getLatencyMs());
            m.put("createdAt", l.getCreatedAt());
            return m;
        }).toList();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("total", src.getTotalElements());
        out.put("pages", src.getTotalPages());
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> view(ApiKey k) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("keyId", k.getKeyId());
        m.put("name", k.getName());
        m.put("tenantId", k.getTenantId());
        m.put("plan", k.getPlan());
        m.put("scopes", k.getScopes());
        m.put("categories", k.getCategories());
        m.put("ipWhitelist", k.getIpWhitelist());
        m.put("rateLimitPerHour", k.getRateLimitPerHour());
        m.put("webhookUrl", k.getWebhookUrl());
        m.put("enabled", k.isEnabled());
        m.put("createdAt", k.getCreatedAt());
        m.put("lastUsedAt", k.getLastUsedAt());
        return m;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nullable(Object o) {
        if (o == null) return null;
        String s = o.toString();
        return s.isBlank() ? null : s;
    }
}