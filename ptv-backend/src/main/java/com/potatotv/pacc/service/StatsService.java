package com.potatotv.pacc.service;

import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.InspectSessionRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 统计数据服务：数据大盘所需聚合指标。
 */
@Service
@RequiredArgsConstructor
public class StatsService {

    private final AccountRepository accountRepository;
    private final DetectionEventRepository eventRepository;
    private final RedscreenAlertRepository alertRepository;
    private final CheatRecordRepository cheatRecordRepository;
    private final InspectSessionRepository inspectSessionRepository;
    private final OnlineStatusService onlineStatusService;

    public Map<String, Object> summary(String startDate, String endDate) {
        Instant start = startDate == null ? Instant.now().minus(7, ChronoUnit.DAYS) : Instant.parse(startDate);
        Instant end = endDate == null ? Instant.now() : Instant.parse(endDate);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("online_pteid", onlineStatusService.onlineCount());
        m.put("detections_today", eventRepository.countByOccurredAtBetween(start, end));
        m.put("redscreen_count", alertRepository.countByOccurredAtBetweenAndStateIn(start, end, List.of("PENDING_INSPECT","CONFIRMED","FALSE_POSITIVE")));
        m.put("pending_inspect", alertRepository.countByState("PENDING_INSPECT"));
        m.put("active_inspect", inspectSessionRepository.findByStateOrderByStartedAtDesc("ACTIVE").size());
        m.put("total_cheat_records", cheatRecordRepository.countByRevokedFalse());
        m.put("total_accounts", accountRepository.count());
        m.put("bedrock_players", eventRepository.countByEdition(com.potatotv.pacc.domain.DetectionEvent.Edition.BEDROCK));
        m.put("java_players", eventRepository.countByEdition(com.potatotv.pacc.domain.DetectionEvent.Edition.JAVA));
        return m;
    }

    public List<Map<String, Object>> cheatTypes() {
        Instant start = Instant.now().minus(7, ChronoUnit.DAYS);
        return alertRepository.countGroupByCheatType(start, Instant.now()).stream()
                .map(row -> Map.<String, Object>of("cheat_type", row[0], "count", row[1]))
                .toList();
    }

    /** 近 N 天红屏趋势（按天）。 */
    public List<Map<String, Object>> redscreenTrend(int days) {
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            Instant d = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(i, ChronoUnit.DAYS);
            perDay.put(d.toString().substring(0, 10), 0L);
        }
        alertRepository.countGroupByCheatType(Instant.now().minus(days, ChronoUnit.DAYS), Instant.now());
        // 简化：按日期逐日统计红屏条数
        IntStream.range(0, days).forEach(i -> {
            Instant dayStart = Instant.now().truncatedTo(ChronoUnit.DAYS).minus(i, ChronoUnit.DAYS);
            Instant dayEnd = dayStart.plus(1, ChronoUnit.DAYS);
            long c = alertRepository.countByOccurredAtBetweenAndStateIn(dayStart, dayEnd, List.of("PENDING_INSPECT","CONFIRMED","FALSE_POSITIVE"));
            perDay.put(dayStart.toString().substring(0, 10), c);
        });
        return perDay.entrySet().stream()
                .map(e -> Map.<String, Object>of("date", e.getKey(), "count", e.getValue()))
                .toList();
    }
}