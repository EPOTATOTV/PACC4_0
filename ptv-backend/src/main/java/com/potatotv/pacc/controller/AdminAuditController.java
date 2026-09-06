package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.AdminOperationLog;
import com.potatotv.pacc.repository.AdminOperationLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.8 管理员操作审计查询与大盘（受 X-Admin-Key 保护）。
 * <p>提供敏感写操作审计日志的过滤查询、异常操作趋势、状态码分布与高频操作分析。</p>
 */
@RestController
@RequestMapping("/api/admin/audit")
@RequiredArgsConstructor
public class AdminAuditController {

    private final AdminOperationLogRepository repository;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    @GetMapping("/operations")
    public Map<String, Object> operations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        Instant start = startDate == null || startDate.isBlank()
                ? Instant.now().minus(30, java.time.temporal.ChronoUnit.DAYS) : Instant.parse(startDate);
        Instant end = endDate == null || endDate.isBlank()
                ? Instant.now() : Instant.parse(endDate);
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.min(50, Math.max(1, size)));
        Page<AdminOperationLog> src;
        if (actor != null && !actor.isBlank()) {
            src = repository.findByActorAndCreatedAtBetween(actor, start, end, pg);
        } else if (action != null && !action.isBlank()) {
            src = repository.findByActionAndCreatedAtBetween(action, start, end, pg);
        } else {
            src = repository.findByCreatedAtBetween(start, end, pg);
        }
        List<Map<String, Object>> rows = src.getContent().stream().map(o -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", o.getId());
            m.put("actor", o.getActor());
            m.put("role", o.getRole());
            m.put("action", o.getAction());
            m.put("entity_type", o.getEntityType());
            m.put("entity_id", o.getEntityId());
            m.put("detail", o.getDetail());
            m.put("ip", o.getIp());
            m.put("http_method", o.getHttpMethod());
            m.put("http_path", o.getHttpPath());
            m.put("http_status", o.getHttpStatus());
            m.put("created_at", o.getCreatedAt());
            return m;
        }).toList();
        return Map.of("rows", rows, "total", src.getTotalElements(), "page", src.getNumber(), "total_pages", src.getTotalPages());
    }

    /** 异常操作趋势：近 N 天敏感写操作数量。 */
    @GetMapping("/trend")
    public Map<String, Object> trend(@RequestParam(defaultValue = "30") int days,
                                     @RequestParam(required = false) String startDate,
                                     @RequestParam(required = false) String endDate) {
        days = Math.max(7, Math.min(365, days));
        Instant start = startDate == null || startDate.isBlank()
                ? Instant.now().minus(days - 1L, java.time.temporal.ChronoUnit.DAYS).truncatedTo(java.time.temporal.ChronoUnit.DAYS)
                : Instant.parse(startDate);
        Instant end = endDate == null || endDate.isBlank() ? Instant.now() : Instant.parse(endDate);
        Map<String, Long> perDay = new LinkedHashMap<>();
        for (Instant d = start; !d.isAfter(end); d = d.plus(1, java.time.temporal.ChronoUnit.DAYS)) {
            perDay.put(d.atZone(ZONE).toLocalDate().toString(), 0L);
        }
        for (Object[] row : repository.countGroupByDay(start, end.plus(1, java.time.temporal.ChronoUnit.MINUTES))) {
            perDay.merge(((Instant) row[0]).atZone(ZONE).toLocalDate().toString(), ((Number) row[1]).longValue(), Long::sum);
        }
        return Map.of("days", new ArrayList<>(perDay.keySet()), "counts", new ArrayList<>(perDay.values()));
    }

    /** 状态码分布 + 高频操作 TOP10（异常分析）。 */
    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "30") int days) {
        Instant start = Instant.now().minus(days, java.time.temporal.ChronoUnit.DAYS);
        Instant end = Instant.now();
        Map<String, Long> status = new LinkedHashMap<>();
        for (Object[] row : repository.countGroupByStatus(start, end.plus(1, java.time.temporal.ChronoUnit.MINUTES))) {
            status.put(String.valueOf(row[0]), ((Number) row[1]).longValue());
        }
        long error = status.entrySet().stream()
                .filter(e -> Integer.parseInt(e.getKey()) >= 400)
                .mapToLong(Map.Entry::getValue).sum();
        long total = status.values().stream().mapToLong(Long::longValue).sum();
        List<Map<String, Object>> top = new ArrayList<>();
        for (Object[] row : repository.countGroupByAction(start, end.plus(1, java.time.temporal.ChronoUnit.MINUTES))) {
            top.add(Map.of("action", String.valueOf(row[0]), "count", ((Number) row[1]).longValue()));
            if (top.size() >= 10) break;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", total);
        m.put("error_count", error);
        m.put("error_rate", total == 0 ? 0.0 : Math.round(error * 10000.0 / total) / 100.0);
        m.put("by_status", status);
        m.put("top_actions", top);
        return m;
    }
}