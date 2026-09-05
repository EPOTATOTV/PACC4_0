package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.ScanIntensityService;
import com.potatotv.pacc.service.ScanIntensityService.ScanProfile;
import com.potatotv.pacc.service.StatsService;
import com.potatotv.pacc.domain.SuspicionFlag;
import com.potatotv.pacc.repository.SuspicionFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 数据大盘接口。受 X-Admin-Key 保护。
 */
@RestController
@RequestMapping("/api/admin/stats")
@RequiredArgsConstructor
public class StatsController {

    private final StatsService statsService;
    private final ScanIntensityService scanIntensityService;
    private final SuspicionFlagRepository suspicionFlagRepository;

    /** 单次查询扫描档位（供客户端拉取）。 */
    @GetMapping("/scan-profile")
    public Map<String, Object> scanProfile(@RequestParam(defaultValue = "LOW") String tier,
                                           @RequestParam(defaultValue = "false") boolean recentRedscreen,
                                           @RequestParam(defaultValue = "NORMAL") String scenario) {
        ScanProfile p = scanIntensityService.profile(
                com.potatotv.pacc.domain.ConfidenceTier.valueOf(tier.toUpperCase()),
                recentRedscreen, scanScenario(scenario));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("mode", p.mode());
        m.put("interval_ms", p.intervalMs());
        m.put("depth", p.depth());
        return m;
    }

    /** 全部等级 + 场景的档位表（供配置/文档）。 */
    @GetMapping("/scan-profiles")
    public Map<String, Object> scanProfiles() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (com.potatotv.pacc.domain.ConfidenceTier t : com.potatotv.pacc.domain.ConfidenceTier.values()) {
            ScanProfile p = scanIntensityService.profile(t, false, ScanIntensityService.Scenario.NORMAL);
            out.put(t.name(), Map.of("mode", p.mode(), "interval_ms", p.intervalMs(), "depth", p.depth()));
        }
        return out;
    }

    private ScanIntensityService.Scenario scanScenario(String s) {
        try {
            return ScanIntensityService.Scenario.valueOf(s.toUpperCase());
        } catch (Exception e) {
            return ScanIntensityService.Scenario.NORMAL;
        }
    }

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

    /** 置信度分级统计（LOW/MEDIUM/HIGH + 阈值）。 */
    @GetMapping("/confidence")
    public Map<String, Object> confidence() {
        return statsService.confidence();
    }

    /** v4.5 对抗分级统计：硬件作弊 / 完整性破坏两类嫌疑的数量分布。 */
    @GetMapping("/countermeasure")
    public Map<String, Object> countermeasure() {
        return Map.of(
                "hardware_cheat_flags", suspicionFlagRepository.countByKind(SuspicionFlag.Kind.HARDWARE_CHEAT),
                "tampered_integrity_flags", suspicionFlagRepository.countByKind(SuspicionFlag.Kind.TAMPERED_INTEGRITY));
    }
}