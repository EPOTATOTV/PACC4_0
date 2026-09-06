package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.BiReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.8 数据平台与 BI：预置报表 REST API（受 X-Admin-Key 保护）。
 * <p>提供检测/红屏趋势、作弊类型分布、误报率、玩家信誉/状态分布、版本分布、
 * 申诉漏斗、管理登录审计等报表，供前端 BI 页渲染与导出。</p>
 */
@RestController
@RequestMapping("/api/admin/bi")
@RequiredArgsConstructor
public class BiController {

    private final BiReportService bi;

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
}