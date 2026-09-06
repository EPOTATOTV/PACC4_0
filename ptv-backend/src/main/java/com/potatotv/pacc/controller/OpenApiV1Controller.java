package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.ApiKey;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.domain.Signature;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import com.potatotv.pacc.repository.SignatureRepository;
import com.potatotv.pacc.service.OpsService;
import com.potatotv.pacc.service.OpenApiWebhookService;
import com.potatotv.pacc.service.StatsService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.8 开放 API（/api/v1/**）：由 {@code ApiV1AuthFilter} 完成 API Key + HMAC 鉴权。
 * 提供检测记录/红屏事件/玩家信誉/策略/特征库/统计 6 大类接口，可配置类别可用与读写范围。
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OpenApiV1Controller {

    private final CheatRecordRepository cheatRecordRepository;
    private final RedscreenAlertRepository alertRepository;
    private final AccountRepository accountRepository;
    private final SignatureRepository signatureRepository;
    private final StatsService statsService;
    private final OpsService opsService;
    private final OpenApiWebhookService webhookService;

    // ---------- 检测记录 ----------
    @GetMapping("/detections")
    public ResponseEntity<Map<String, Object>> detections(
            @RequestParam(required = false) String pteid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        if (!catAllowed(req, "detections")) return forbidden();
        Pageable pg = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        var src = (pteid == null || pteid.isBlank())
                ? cheatRecordRepository.findAllByOrderByOccurredAtDesc(pg)
                : cheatRecordRepository.findByPteidOrderByOccurredAtDesc(pteid, pg);
        return ResponseEntity.ok(pageOf(src.getContent().stream().map(OpenApiV1Controller::cheatDto).toList(),
                src.getTotalElements(), src.getTotalPages()));
    }

    @GetMapping("/detections/{recordId}")
    public ResponseEntity<?> detection(@PathVariable String recordId, HttpServletRequest req) {
        if (!catAllowed(req, "detections")) return forbidden();
        var rec = cheatRecordRepository.findById(recordId).orElse(null);
        if (rec == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(cheatDto(rec));
    }

    // ---------- 红屏事件 ----------
    @GetMapping("/redscreen/events")
    public ResponseEntity<Map<String, Object>> redscreenEvents(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        if (!catAllowed(req, "redscreen")) return forbidden();
        Pageable pg = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        var src = alertRepository.findAll(pg);
        return ResponseEntity.ok(pageOf(src.getContent().stream().map(OpenApiV1Controller::alertDto).toList(),
                src.getTotalElements(), src.getTotalPages()));
    }

    @GetMapping("/redscreen/active")
    public ResponseEntity<?> redscreenActive(HttpServletRequest req) {
        if (!catAllowed(req, "redscreen")) return forbidden();
        return ResponseEntity.ok(alertRepository.findByStateOrderByOccurredAtDesc("PENDING_INSPECT")
                .stream().map(OpenApiV1Controller::alertDto).toList());
    }

    @PostMapping("/redscreen/{alertId}/unlock")
    public ResponseEntity<Map<String, Object>> unlock(@PathVariable String alertId, HttpServletRequest req) {
        if (!catAllowed(req, "redscreen")) return forbidden();
        if (!Boolean.TRUE.equals(req.getAttribute("apiWriteName"))) {
            return ResponseEntity.status(403).body(Map.of("error", "仅读密钥不可执行写操作"));
        }
        int n = alertRepository.unlockByAlertId(alertId, "FALSE_POSITIVE");
        if (n <= 0) return ResponseEntity.status(404).body(Map.of("error", "红屏不存在或已处理"));
        ApiKey key = (ApiKey) req.getAttribute("apiKey");
        webhookService.push(key, "redscreen.unlocked", Map.of("alert_id", alertId));
        return ResponseEntity.ok(Map.of("unlocked", true, "alert_id", alertId));
    }

    // ---------- 玩家信誉 ----------
    @GetMapping("/players/{pteid}/reputation")
    public ResponseEntity<?> reputation(@PathVariable String pteid, HttpServletRequest req) {
        if (!catAllowed(req, "players")) return forbidden();
        Account a = accountRepository.findById(pteid).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("pteid", a.getPteid());
        m.put("reputation", a.getReputation());
        m.put("status", a.getStatus());
        m.put("total_redscreen", a.getTotalRedscreen());
        m.put("last_redscreen_at", a.getLastRedScreenTime());
        return ResponseEntity.ok(m);
    }

    // ---------- 策略 ----------
    @GetMapping("/policy")
    public ResponseEntity<Map<String, Object>> policy(HttpServletRequest req) {
        if (!catAllowed(req, "policy")) return forbidden();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", opsService.activeConfig());
        return ResponseEntity.ok(out);
    }

    // ---------- 特征库 ----------
    @GetMapping("/signatures")
    public ResponseEntity<Map<String, Object>> signatures(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            HttpServletRequest req) {
        if (!catAllowed(req, "signatures")) return forbidden();
        Pageable pg = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        var src = signatureRepository.findAll(pg);
        return ResponseEntity.ok(pageOf(src.getContent().stream().map(OpenApiV1Controller::signatureDto).toList(),
                src.getTotalElements(), src.getTotalPages()));
    }

    // ---------- 统计 ----------
    @GetMapping("/stats/overview")
    public ResponseEntity<Map<String, Object>> statsOverview(HttpServletRequest req) {
        if (!catAllowed(req, "stats")) return forbidden();
        return ResponseEntity.ok(statsService.summary(null, null));
    }

    // ---------- 辅助 ----------
    private boolean catAllowed(HttpServletRequest req, String cat) {
        ApiKey key = (ApiKey) req.getAttribute("apiKey");
        if (key == null) return false;
        String cats = key.getCategories();
        if (cats == null || cats.isBlank()) return true; // 空=全部
        for (String c : cats.split(",")) {
            if (c.trim().equalsIgnoreCase(cat)) return true;
        }
        return false;
    }

    private ResponseEntity<Map<String, Object>> forbidden() {
        return ResponseEntity.status(403).body(Map.of("error", "密钥无权访问该 API 类别"));
    }

    private static Map<String, Object> pageOf(List<Map<String, Object>> items, long total, int pages) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("items", items);
        m.put("total", total);
        m.put("pages", pages);
        return m;
    }

    private static Map<String, Object> cheatDto(CheatRecord r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("recordId", r.getRecordId());
        m.put("pteid_masked", pteidMasked(r.getPteid()));
        m.put("cheatType", r.getCheatType());
        m.put("level", r.getLevel());
        m.put("riskScore", r.getRiskScore());
        m.put("occurredAt", r.getOccurredAt());
        m.put("revoked", r.isRevoked());
        return m;
    }

    private static Map<String, Object> alertDto(RedscreenAlert a) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("alertId", a.getAlertId());
        m.put("level", a.getLevel());
        m.put("cheatType", a.getCheatType());
        m.put("state", a.getState());
        m.put("riskScore", a.getRiskScore());
        m.put("occurredAt", a.getOccurredAt());
        return m;
    }

    private static Map<String, Object> signatureDto(Signature s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("name", s.getName());
        m.put("riskLevel", s.getRiskLevel());
        m.put("state", s.getState());
        m.put("version", s.getVersion());
        m.put("edition", s.getEdition());
        return m;
    }

    private static String pteidMasked(String pteid) {
        if (pteid == null || pteid.length() < 4) return "****";
        return pteid.substring(0, 2) + "***" + pteid.substring(pteid.length() - 2);
    }
}