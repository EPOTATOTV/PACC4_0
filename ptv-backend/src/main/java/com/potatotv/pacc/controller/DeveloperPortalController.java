package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.ApiKey;
import com.potatotv.pacc.domain.ApiUsageLog;
import com.potatotv.pacc.repository.ApiUsageLogRepository;
import com.potatotv.pacc.service.ApiKeyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v5.0 开发者生态：开发者自助门户（/api/dev/**）。
 * <p>与开放数据 API 共用同一套 API Key + HMAC 鉴权（见 {@link com.potatotv.pacc.config.ApiV1AuthFilter}）；
 * 开发者凭自身密钥自助查看配额/用量、轮换密钥、配置 Webhook，实现对应用编排的自主管理。</p>
 */
@RestController
@RequestMapping("/api/dev")
@RequiredArgsConstructor
public class DeveloperPortalController {

    private final ApiKeyService keyService;
    private final ApiUsageLogRepository usageRepo;

    private static ApiKey keyOf(HttpServletRequest req) {
        Object key = req.getAttribute("apiKey");
        if (!(key instanceof ApiKey k)) {
            throw new IllegalStateException("未鉴权的开发者请求");
        }
        return k;
    }

    /** 查看自身密钥信息（不含明文 secret）与配额。 */
    @GetMapping("/me")
    public Object me(HttpServletRequest req) {
        ApiKey key = keyOf(req);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key_id", key.getKeyId());
        m.put("name", key.getName());
        m.put("tenant_id", key.getTenantId());
        m.put("plan", key.getPlan());
        m.put("scopes", key.getScopes());
        m.put("rate_limit_per_hour", key.getRateLimitPerHour());
        m.put("categories", key.getCategories());
        m.put("ip_whitelist", key.getIpWhitelist());
        m.put("webhook_url", key.getWebhookUrl());
        m.put("enabled", key.isEnabled());
        m.put("created_at", key.getCreatedAt());
        return m;
    }

    /** 轮换自身密钥：新明文 secret 仅此一次返回。 */
    @PostMapping("/rotate")
    public Object rotate(HttpServletRequest req) {
        ApiKey key = keyOf(req);
        ApiKeyService.CreatedKey ck = keyService.rotate(key.getKeyId());
        return Map.of("key_id", ck.key().getKeyId(), "secret", ck.secret(), "note", "secret shown once");
    }

    /** 配置自身 Webhook 推送地址与签名密钥。 */
    @PutMapping("/webhook")
    public Object webhook(HttpServletRequest req, @RequestBody Map<String, String> body) {
        ApiKey key = keyOf(req);
        keyService.updateWebhook(key.getKeyId(), body.get("url"), body.get("secret"));
        return Map.of("updated", key.getKeyId());
    }

    /** 查询自身近 24h / 指定小时的调用审计。 */
    @GetMapping("/usage")
    public Object usage(HttpServletRequest req,
                        @RequestParam(defaultValue = "24") int hours,
                        @RequestParam(defaultValue = "0") int page,
                        @RequestParam(defaultValue = "30") int size) {
        ApiKey key = keyOf(req);
        hours = Math.max(1, Math.min(168, hours));
        Instant after = Instant.now().minus(hours, ChronoUnit.HOURS);
        long used = usageRepo.countByApiKeyIdAndCreatedAtAfter(key.getKeyId(), after);
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)));
        var src = usageRepo.findByApiKeyIdOrderByCreatedAtDesc(key.getKeyId(), pg);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ApiUsageLog l : src.getContent()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("method", l.getMethod());
            row.put("path", l.getPath());
            row.put("status_code", l.getStatusCode());
            row.put("latency_ms", l.getLatencyMs());
            row.put("created_at", l.getCreatedAt());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("used_last_" + hours + "h", used);
        out.put("rows", rows);
        out.put("total", src.getTotalElements());
        return out;
    }
}