package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.RemoteConfig;
import com.potatotv.pacc.service.OpsService;
import com.potatotv.pacc.service.OnlineStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.7 自动化运维管理接口（受 X-Admin-Key / 管理会话保护）：
 * 服务健康、客户端崩溃/性能上报与查询、远程配置。
 * 注：当前统一在 /api/admin/ops/** 下由管理认证保护；若客户端需直接匿名上报，可另开
 * /api/ops/report/* 的放行路径（需配套限流/共享密钥），此处不破坏现有安全配置。
 */
@RestController
@RequestMapping("/api/admin/ops")
@RequiredArgsConstructor
public class OpsController {

    private final OpsService opsService;
    private final JdbcTemplate jdbcTemplate;
    private final OnlineStatusService onlineStatusService;

    /** 服务健康：DB 连通性 + 内存 + 运行时长 + WSS 在玩家在线数。 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        String db = "UP";
        String dbErr = null;
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        } catch (Exception e) {
            db = "DOWN";
            dbErr = e.getClass().getSimpleName();
        }
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
        long maxMb = rt.maxMemory() / (1024 * 1024);
        long uptimeSec = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", db.equals("UP") ? "UP" : "DEGRADED");
        m.put("db", db);
        if (dbErr != null) m.put("db_error", dbErr);
        m.put("memory", Map.of("used_mb", usedMb, "max_mb", maxMb));
        m.put("uptime_seconds", uptimeSec);
        try {
            m.put("players_online", onlineStatusService.onlineCount());
        } catch (Exception e) {
            m.put("players_online", -1);
        }
        return m;
    }

    /** 客户端崩溃上报（落库）。 */
    @PostMapping("/report/crash")
    public ResponseEntity<?> reportCrash(@RequestBody Map<String, Object> body) {
        String clientVersion = str(body.get("client_version"), body.get("clientVersion"));
        if (clientVersion == null || clientVersion.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "client_version required"));
        }
        var saved = opsService.reportCrash(
                str(body.get("pteid"), null),
                clientVersion,
                str(body.get("os"), ""),
                str(body.get("arch"), ""),
                str(body.get("platform"), "WINDOWS"),
                str(body.get("stack_trace"), body.get("stackTrace")),
                str(body.get("context_json"), body.get("contextJson")),
                str(body.get("controller"), null));
        return ResponseEntity.ok(Map.of("id", saved.getId()));
    }

    /** 客户端性能上报（落库）。 */
    @PostMapping("/report/telemetry")
    public ResponseEntity<?> reportTelemetry(@RequestBody Map<String, Object> body) {
        String clientVersion = str(body.get("client_version"), body.get("clientVersion"));
        if (clientVersion == null || clientVersion.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "client_version required"));
        }
        var saved = opsService.reportTelemetry(
                str(body.get("pteid"), null),
                clientVersion,
                str(body.get("os"), ""),
                dbl(body.get("cpu_percent"), body.get("cpuPercent"), 0.0),
                lng(body.get("mem_mb"), body.get("memMb"), 0L),
                dblNullable(body.get("fps_impact_percent"), body.get("fpsImpactPercent")),
                lngNullable(body.get("detection_latency_ms"), body.get("detectionLatencyMs")));
        return ResponseEntity.ok(Map.of("id", saved.getId()));
    }

    /** 最近崩溃列表。 */
    @GetMapping("/crashes")
    public Map<String, Object> crashes(@RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("crashes", opsService.recentCrashes(limit));
        return m;
    }

    /** 最近性能上报列表。 */
    @GetMapping("/telemetry")
    public Map<String, Object> telemetry(@RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("telemetry", opsService.recentTelemetry(limit));
        return m;
    }

    /** 运维概览。 */
    @GetMapping("/overview")
    public Map<String, Object> overview() {
        return opsService.overview();
    }

    /** 远程配置全部。 */
    @GetMapping("/config")
    public Map<String, Object> config() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configs", opsService.listConfig());
        return m;
    }

    /** 更新单条远程配置（upsert）。 */
    @PutMapping("/config/{key}")
    public ResponseEntity<?> upsertConfig(@PathVariable String key,
                                          @RequestBody Map<String, Object> body) {
        String category = str(body.get("category"), null);
        if (category == null || category.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "category required (DETECTION|SCAN|REDSCREEN|THROTTLE)"));
        }
        Integer intValue = intNullable(body.get("int_value"), body.get("intValue"));
        Double doubleValue = dblNullable(body.get("double_value"), body.get("doubleValue"));
        Boolean boolValue = boolNullable(body.get("bool_value"), body.get("boolValue"));
        RemoteConfig saved = opsService.upsertConfig(key, category, intValue, doubleValue, boolValue,
                str(body.get("updated_by"), body.get("updatedBy")));
        return ResponseEntity.ok(saved);
    }

    /** 生效配置映射（key → 值）。 */
    @GetMapping("/config/active")
    public Map<String, Object> activeConfig() {
        return opsService.activeConfig();
    }

    private static String str(Object o, Object fallback) {
        Object v = o != null ? o : fallback;
        return v == null ? null : v.toString();
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

    private static Integer intNullable(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Boolean boolNullable(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        if (v == null) return null;
        if (v instanceof Boolean b) return b;
        return Boolean.parseBoolean(v.toString());
    }
}