package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.BiDashboardService;
import com.potatotv.pacc.service.BiReportService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.8 数据平台与 BI：预置报表 REST API（受 X-Admin-Key 保护）。
 * <p>提供检测/红屏趋势、作弊类型分布、误报率、玩家信誉/状态分布、版本分布、
 * 申诉漏斗、管理登录审计等报表，供前端 BI 页渲染与导出。
 * 另含报表 CSV 导出、实时大屏快照、数据下钻明细与自定义仪表盘 CRUD。</p>
 */
@RestController
@RequestMapping("/api/admin/bi")
@RequiredArgsConstructor
public class BiController {

    private final BiReportService bi;
    private final BiDashboardService dashboards;

    private static final int MAX_DAYS = 365;

    private static int cap(int days) {
        return Math.max(7, Math.min(MAX_DAYS, days == 0 ? 30 : Math.abs(days)));
    }

    private static Instant rangeStart(int days, String startDate) {
        if (startDate != null && !startDate.isBlank()) return Instant.parse(startDate);
        return Instant.now().minus(cap(days), ChronoUnit.DAYS);
    }

    private static Instant rangeEnd(String endDate) {
        if (endDate != null && !endDate.isBlank()) return Instant.parse(endDate);
        return Instant.now();
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") int days,
                                        @RequestParam(required = false) String startDate,
                                        @RequestParam(required = false) String endDate) {
        int d = cap(days);
        Instant start = rangeStart(d, startDate);
        Instant end = rangeEnd(endDate);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("detection_trend", bi.detectionTrend(d, startDate, endDate));
        m.put("redscreen_trend", bi.redscreenTrend(d, startDate, endDate));
        m.put("cheat_types", bi.cheatTypes(start, end));
        m.put("redscreen_health", bi.redscreenHealth(start, end));
        m.put("cheat_record_health", bi.cheatRecordHealth());
        m.put("player_profile", bi.playerProfile());
        m.put("edition_split", bi.editionSplit(start, end));
        m.put("appeal_funnel", bi.appealFunnel(start, end));
        m.put("login_audit", bi.loginAudit(d, startDate, endDate));
        return m;
    }

    @GetMapping("/detection-trend")
    public Map<String, Object> detectionTrend(@RequestParam(defaultValue = "30") int days,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate) {
        return bi.detectionTrend(cap(days), startDate, endDate);
    }

    @GetMapping("/redscreen-trend")
    public Map<String, Object> redscreenTrend(@RequestParam(defaultValue = "30") int days,
                                              @RequestParam(required = false) String startDate,
                                              @RequestParam(required = false) String endDate) {
        return bi.redscreenTrend(cap(days), startDate, endDate);
    }

    @GetMapping("/cheat-types")
    public Map<String, Object> cheatTypes(@RequestParam(required = false) String startDate,
                                          @RequestParam(required = false) String endDate) {
        return bi.cheatTypes(rangeStart(30, startDate), rangeEnd(endDate));
    }

    @GetMapping("/redscreen-health")
    public Map<String, Object> redscreenHealth(@RequestParam(required = false) String startDate,
                                               @RequestParam(required = false) String endDate) {
        return bi.redscreenHealth(rangeStart(30, startDate), rangeEnd(endDate));
    }

    @GetMapping("/player-profile")
    public Map<String, Object> playerProfile() {
        return bi.playerProfile();
    }

    @GetMapping("/appeal-funnel")
    public Map<String, Object> appealFunnel(@RequestParam(required = false) String startDate,
                                            @RequestParam(required = false) String endDate) {
        return bi.appealFunnel(rangeStart(30, startDate), rangeEnd(endDate));
    }

    @GetMapping("/login-audit")
    public Map<String, Object> loginAudit(@RequestParam(defaultValue = "30") int days,
                                          @RequestParam(required = false) String startDate,
                                          @RequestParam(required = false) String endDate) {
        return bi.loginAudit(cap(days), startDate, endDate);
    }

    // ---------- v4.8 BI 补全：导出 / 实时大屏 / 数据下钻 / 自定义仪表盘 ----------

    /** 报表 CSV 导出（attachment）。report: detection-trend / redscreen-trend / login-audit / cheat-types。 */
    @GetMapping("/export")
    public void export(@RequestParam String report,
                       @RequestParam(defaultValue = "30") int days,
                       @RequestParam(required = false) String startDate,
                       @RequestParam(required = false) String endDate,
                       HttpServletResponse response) throws IOException {
        String csv = bi.exportCsv(report, cap(days), startDate, endDate);
        response.setContentType("text/csv; charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + report + ".csv");
        response.getWriter().write(csv);
    }

    /** 实时大屏快照：在线数、近期检测/红屏量、待查验红屏、最新红屏事件流。 */
    @GetMapping("/realtime")
    public Map<String, Object> realtime() {
        return bi.realtime();
    }

    /** 检测明细下钻（最近 50 条，时间倒序）。 */
    @GetMapping("/drill/detections")
    public List<Map<String, Object>> drillDetections(@RequestParam(defaultValue = "30") int days,
                                                     @RequestParam(required = false) String startDate) {
        return bi.detectionDrilldown(cap(days), startDate);
    }

    /** 红屏明细下钻（最近 50 条，时间倒序；pteid 脱敏）。 */
    @GetMapping("/drill/redscreens")
    public List<Map<String, Object>> drillRedscreens(@RequestParam(defaultValue = "30") int days,
                                                     @RequestParam(required = false) String startDate) {
        return bi.redscreenDrilldown(cap(days), startDate);
    }

    /** 自定义仪表盘列表（按租户隔离，默认 platform）。 */
    @GetMapping("/dashboards")
    public Object dashboards(@RequestParam(required = false) String tenantId) {
        return dashboards.list(tenantId);
    }

    /** 新建自定义仪表盘。 */
    @PostMapping("/dashboards")
    public Object createDashboard(@RequestBody Map<String, String> body) {
        String tenantId = body.getOrDefault("tenantId", "platform");
        String createdBy = body.get("createdBy");
        return dashboards.create(tenantId, body.get("name"), body.get("widgets"), body.get("layout"), createdBy);
    }

    /** 更新自定义仪表盘。 */
    @PutMapping("/dashboards/{id}")
    public Object updateDashboard(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return dashboards.update(id, body.get("name"), body.get("widgets"), body.get("layout"));
    }

    /** 删除自定义仪表盘。 */
    @DeleteMapping("/dashboards/{id}")
    public Map<String, Object> deleteDashboard(@PathVariable Long id) {
        dashboards.delete(id);
        return Map.of("deleted", id);
    }
}