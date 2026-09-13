package com.potatotv.pacc.service;

import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import com.potatotv.pacc.repository.SuspicionFlagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 反作弊效果分析：基于现有检测/红屏/作弊记录/申诉统计得出检出量、误报率、
 * 有效拦截率与外挂类型分布（命中黑名单/白名单对照由名单服务补充）。
 */
@Service
@RequiredArgsConstructor
public class EffectivenessService {

    private final CheatRecordRepository cheatRecordRepository;
    private final AppealRepository appealRepository;
    private final RedscreenAlertRepository redscreenAlertRepository;
    private final SuspicionFlagRepository suspicionFlagRepository;

    public Map<String, Object> summary() {
        long totalRecords = cheatRecordRepository.count();
        long confirmed = cheatRecordRepository.countByRevokedFalse();
        long falsePositive = cheatRecordRepository.countRevokedTrue();
        long appealsApproved = appealRepository.countByStatus("approved");

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("total_cheat_records", totalRecords);
        out.put("confirmed_cheats", confirmed);
        out.put("false_positives", falsePositive);
        out.put("appeals_approved", appealsApproved);
        out.put("total_redscreens", redscreenAlertRepository.count());
        out.put("total_suspicion_flags", suspicionFlagRepository.count());

        double classified = confirmed + falsePositive;
        double precision = classified <= 0 ? 0 : round(confirmed / classified * 100);
        double falsePositiveRate = classified <= 0 ? 0 : round(falsePositive / classified * 100);
        out.put("precision_pct", precision);
        out.put("false_positive_pct", falsePositiveRate);

        // 误报率低于阈值视为健康
        boolean healthy = totalRecords > 0 && falsePositiveRate <= 5.0;
        out.put("healthy", healthy);
        return out;
    }

    public List<Map<String, Object>> cheatTypeDistribution() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] row : cheatRecordRepository.countGroupByCheatType()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cheat_type", row[0] == null ? "unknown" : String.valueOf(row[0]));
            m.put("count", row[1] == null ? 0L : ((Number) row[1]).longValue());
            rows.add(m);
        }
        return rows;
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}