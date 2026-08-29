package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.StatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 数据大盘接口。受 X-Admin-Key 保护。
 */
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;

    @GetMapping("/summary")
    public Map<String, Object> summary(@RequestParam(required = false) String startDate,
                                       @RequestParam(required = false) String endDate) {
        return statsService.summary(startDate, endDate);
    }

    @GetMapping("/cheat-types")
    public Object cheatTypes() {
        return statsService.cheatTypes();
    }

    @GetMapping("/redscreen-trend")
    public Object redscreenTrend(@RequestParam(defaultValue = "7") int days) {
        return statsService.redscreenTrend(days);
    }
}