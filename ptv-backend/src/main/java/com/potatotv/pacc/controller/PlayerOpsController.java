package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Signature;
import com.potatotv.pacc.service.OpsService;
import com.potatotv.pacc.service.SignatureLibraryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.7 玩家端运维上报（受玩家 JWT 保护）：
 * 客户端周期性上报性能、崩溃时上报堆栈，拉取生效的远程配置用于端侧阈值调整，
 * 并同步已签名的特征库增量以便端侧热更新。
 * <p>数据入口与管理端共用 {@link OpsService}/{@link SignatureLibraryService}，管理端查看与聚合。</p>
 */
@RestController
@RequestMapping("/api/player/ops")
@RequiredArgsConstructor
public class PlayerOpsController {

    private final OpsService opsService;
    private final SignatureLibraryService signatureLibraryService;

    /** 客户端性能上报。 */
    @PostMapping("/report/telemetry")
    public ResponseEntity<?> reportTelemetry(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        String clientVersion = str(body.get("client_version"), body.get("clientVersion"));
        if (clientVersion.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "client_version required"));
        }
        var saved = opsService.reportTelemetry(
                pteid,
                clientVersion,
                str(body.get("os"), body.get("os")),
                dbl(body.get("cpu_percent"), body.get("cpuPercent"), 0.0),
                lng(body.get("mem_mb"), body.get("memMb"), 0L),
                dblNullable(body.get("fps_impact_percent"), body.get("fpsImpactPercent")),
                lngNullable(body.get("detection_latency_ms"), body.get("detectionLatencyMs")));
        return ResponseEntity.ok(Map.of("id", saved.getId()));
    }

    /** 客户端崩溃上报。 */
    @PostMapping("/report/crash")
    public ResponseEntity<?> reportCrash(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        String clientVersion = str(body.get("client_version"), body.get("clientVersion"));
        if (clientVersion.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "client_version required"));
        }
        var saved = opsService.reportCrash(
                pteid,
                clientVersion,
                str(body.get("os"), ""),
                str(body.get("arch"), ""),
                str(body.get("platform"), "WINDOWS"),
                str(body.get("stack_trace"), body.get("stackTrace")),
                str(body.get("context_json"), body.get("contextJson")),
                str(body.get("controller"), null));
        return ResponseEntity.ok(Map.of("id", saved.getId()));
    }

    /** 生效远程配置（key → bool/int/double），客户端据此调整端侧采样/阈值。 */
    @GetMapping("/config/active")
    public ResponseEntity<Map<String, Object>> activeConfig(HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        Map<String, Object> cfg = opsService.activeConfig();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("config", cfg);
        out.put("updated_at", System.currentTimeMillis());
        return ResponseEntity.ok(out);
    }

    /** 已签名的特征库增量（供端侧热更新）；客户端仅接受能通过摘要+签名校验的内容。 */
    @GetMapping("/signatures")
    public ResponseEntity<?> signatures(@RequestParam(required = false) Long afterVersion,
                                        @RequestParam(defaultValue = "JAVA") String edition,
                                        HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        Signature.Edition e;
        try {
            e = Signature.Edition.valueOf(edition.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", "edition 非法"));
        }
        return ResponseEntity.ok(signatureLibraryService.diff(e, afterVersion, true));
    }

    private static String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }

    private static String str(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        return v == null ? "" : v.toString();
    }

    private static Double dblNullable(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        if (v == null) return null;
        if (v instanceof Number n) return n.doubleValue();
        try {
            return Double.parseDouble(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static double dbl(Object primary, Object fallback, double def) {
        Double v = dblNullable(primary, fallback);
        return v == null ? def : v;
    }

    private static Long lngNullable(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static long lng(Object primary, Object fallback, long def) {
        Long v = lngNullable(primary, fallback);
        return v == null ? def : v;
    }
}