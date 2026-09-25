package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.apm.ApmAggregationService;
import com.potatotv.pacc.service.apm.ApmAlertService;
import com.potatotv.pacc.service.apm.ApmCatalog;
import com.potatotv.pacc.service.apm.ApmQueryService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v5.4 §2 APM 管理端端点：概览、趋势、平台矩阵、版本回归、异常、告警、指标目录与手动聚合。
 *
 * <p>权限：只读一律 {@code system:read}，告警列表 {@code alerts:read}、确认 {@code alerts:update}、
 * 手动聚合 {@code system:update}。认证与权限判定由既有的 {@code AdminKeyFilter} +
 * {@code PermissionInterceptor}（{@code /api/admin/**}）统一覆盖。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/apm")
@RequiredArgsConstructor
public class ApmAdminController {

    private final ApmQueryService query;
    private final ApmAlertService alertService;
    private final ApmAggregationService aggregation;

    /** APM 概览卡片 + 指标目录 + 可选筛选值。 */
    @GetMapping("/overview")
    @RequirePermission("system:read")
    public ResponseEntity<?> overview(@RequestParam(defaultValue = "24") int hours,
                                      @RequestParam(required = false) String platform,
                                      @RequestParam(required = false) String clientVer) {
        return ResponseEntity.ok(query.overview(hours, platform, clientVer));
    }

    /** 单指标趋势。 */
    @GetMapping("/trend")
    @RequirePermission("system:read")
    public ResponseEntity<?> trend(@RequestParam String metric,
                                   @RequestParam(defaultValue = "24") int hours,
                                   @RequestParam(required = false) String platform,
                                   @RequestParam(required = false) String clientVer) {
        if (metric == null || metric.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "metric 不能为空"));
        }
        if (ApmCatalog.byName(metric).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知指标: " + metric));
        }
        return ResponseEntity.ok(query.trend(metric, hours, platform, clientVer));
    }

    /** 平台 × 指标矩阵（metrics 为逗号分隔的指标名，省略则用默认指标集）。 */
    @GetMapping("/platform-matrix")
    @RequirePermission("system:read")
    public ResponseEntity<?> platformMatrix(@RequestParam(defaultValue = "24") int hours,
                                            @RequestParam(required = false) String metrics) {
        return ResponseEntity.ok(query.platformMatrix(hours, csv(metrics)));
    }

    /** 版本回归（基准为最早出现的版本）。 */
    @GetMapping("/version-regression")
    @RequirePermission("system:read")
    public ResponseEntity<?> versionRegression(@RequestParam String metric,
                                               @RequestParam(required = false) String platform) {
        if (metric == null || metric.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "metric 不能为空"));
        }
        if (ApmCatalog.byName(metric).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知指标: " + metric));
        }
        return ResponseEntity.ok(query.versionRegression(metric, platform));
    }

    /** 基线偏离异常（默认回看 7 天）。 */
    @GetMapping("/anomalies")
    @RequirePermission("system:read")
    public ResponseEntity<?> anomalies(@RequestParam String metric,
                                       @RequestParam(required = false) String platform,
                                       @RequestParam(defaultValue = "168") int hours) {
        if (metric == null || metric.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "metric 不能为空"));
        }
        if (ApmCatalog.byName(metric).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知指标: " + metric));
        }
        return ResponseEntity.ok(query.anomalies(platform, metric, hours));
    }

    /** 告警列表。 */
    @GetMapping("/alerts")
    @RequirePermission("alerts:read")
    public ResponseEntity<?> alerts(@RequestParam(required = false) String status,
                                    @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(query.alertView(status, limit));
    }

    /** 确认告警（只有 OPEN 可确认）。 */
    @PostMapping("/alerts/{id}/ack")
    @RequirePermission("alerts:update")
    public ResponseEntity<?> ack(@PathVariable String id, HttpServletRequest req) {
        String actor = actorOf(req);
        try {
            return ResponseEntity.ok(ApmAlertService.view(alertService.ack(id, actor)));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    /** 指标目录（前端筛选与图表元数据来源）。 */
    @GetMapping("/catalog")
    @RequirePermission("system:read")
    public ResponseEntity<?> catalog() {
        return ResponseEntity.ok(query.catalog());
    }

    /** 手动触发最近一个整点的聚合与告警评估（运维补跑用）。 */
    @PostMapping("/aggregate")
    @RequirePermission("system:update")
    public ResponseEntity<?> aggregate() {
        Instant closed = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(1, ChronoUnit.HOURS);
        int aggregated = aggregation.aggregateHour(closed);
        int fired = alertService.evaluate(closed);
        log.info("APM 手动聚合 hour={} 分组={} 告警={}", closed, aggregated, fired);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("hour", closed.toString());
        out.put("aggregated", aggregated);
        out.put("alerts", fired);
        return ResponseEntity.ok(out);
    }

    /** 逗号分隔参数解析（空项丢弃）。 */
    private static List<String> csv(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (part != null && !part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    private static String actorOf(HttpServletRequest req) {
        Object v = req.getAttribute("adminActor");
        return v == null ? "api-key" : v.toString();
    }
}