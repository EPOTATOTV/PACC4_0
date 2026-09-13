package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.AlertEvent;
import com.potatotv.pacc.domain.AlertRule;
import com.potatotv.pacc.repository.AlertEventRepository;
import com.potatotv.pacc.repository.AlertRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 告警规则引擎：每轮评估启用规则，将实时指标与阈值对比写成 {@link AlertEvent}。
 * <p>支持确认/解决与按「规则 + 级别」的冷却抑制；指标回落自动 RESOLVED。
 * 实时指标由各 Repository / 在线状态聚合。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 流式聚合的 null 分析误报
public class AlertService {

    private final AlertRuleRepository alertRuleRepository;
    private final AlertEventRepository alertEventRepository;
    private final OnlineStatusService onlineStatusService;
    private final com.potatotv.pacc.repository.RedscreenAlertRepository redscreenRepository;
    private final com.potatotv.pacc.repository.DetectionEventRepository eventRepository;
    private final com.potatotv.pacc.repository.InspectSessionRepository inspectRepository;

    /** 抑制窗口：ruleId:severity -> 最后触发时间，避免短窗口内重复轰炸。 */
    private final Map<String, Instant> suppressedUntil = new ConcurrentHashMap<>();

    /** 每分钟一轮（由 @Scheduled 调度）。 */
    @Transactional
    public void evaluateRules() {
        List<AlertRule> rules = alertRuleRepository.findAllByOrderByIdAsc().stream()
                .filter(AlertRule::isEnabled)
                .toList();
        if (rules.isEmpty()) return;
        for (AlertRule rule : rules) {
            evaluate(rule);
        }
        // 指标已回落的 FIRING 事件自动 RESOLVED
        autoResolveRecovered();
    }

    private void evaluate(AlertRule rule) {
        int threshold = rule.getThreshold() == null ? 0 : rule.getThreshold();
        double actual = metricValue(rule);
        boolean breach = metricBreached(rule, actual);
        if (breach) {
            tryFire(rule, actual);
        } else {
            // 指标恢复正常：确认抑制窗口并解除已开的 FIRING 事件（交由 autoResolveRecovered 处理）
            suppressedUntil.remove(suppressKey(rule, 1));
        }
    }

    /** 依据 scope/condition 计算实时指标；未知指标返回 0，不触发。 */
    private double metricValue(AlertRule rule) {
        String metric = rule.getCondition() == null ? "" : rule.getCondition().toLowerCase();
        Instant from = Instant.now().minus(1, ChronoUnit.MINUTES);
        return switch (metric) {
            case "redscreen_rate" -> redscreenRepository.countByOccurredAtBetween(from, Instant.now());
            case "detection_rate" -> eventRepository.countByOccurredAtBetween(from, Instant.now());
            case "pending_inspect" -> inspectRepository.findAll().stream()
                    .filter(s -> "QUEUED".equals(s.getState())).count();
            case "online_count" -> onlineStatusService.onlineCount();
            default -> 0.0;
        };
    }

    private boolean metricBreached(AlertRule rule, double actual) {
        String metric = rule.getCondition() == null ? "" : rule.getCondition().toLowerCase();
        int threshold = rule.getThreshold() == null ? 0 : rule.getThreshold();
        // 除「在线数下降」这类反向条件外，多数为「超过阈值」
        if ("online_count".equals(metric)) {
            return actual < threshold;
        }
        return actual > threshold;
    }

    private void tryFire(AlertRule rule, double actual) {
        int severity = severityOf(rule.getScope());
        String key = suppressKey(rule, severity);
        Instant cooldownEnd = suppressedUntil.get(key);
        if (cooldownEnd != null && cooldownEnd.isAfter(Instant.now())) {
            return; // 冷却中
        }
        // 已有未解决 FIRING 事件则不再重复生成（避免刷屏），仅更新抑制窗口
        long open = alertEventRepository.findByRuleIdAndStatus(rule.getId(), "FIRING").size();
        if (open > 0) {
            suppressedUntil.put(key, Instant.now().plusSeconds(cooldownSec(rule)));
            return;
        }
        AlertEvent ev = AlertEvent.builder()
                .id(UUID.randomUUID().toString())
                .ruleId(rule.getId())
                .ruleName(rule.getName())
                .severity(severity)
                .metric(rule.getCondition())
                .conditionValue(rule.getCondition())
                .threshold(rule.getThreshold())
                .actualValue((float) actual)
                .status("FIRING")
                .firedAt(Instant.now())
                .createdAt(Instant.now())
                .build();
        alertEventRepository.save(ev);
        suppressedUntil.put(key, Instant.now().plusSeconds(cooldownSec(rule)));
        log.warn("告警规则触发 rule={} metric={} threshold={} actual={}", rule.getName(),
                rule.getCondition(), rule.getThreshold(), actual);
    }

    private void autoResolveRecovered() {
        Instant now = Instant.now();
        for (AlertEvent ev : alertEventRepository.findByStatusOrderByFiredAtDesc("FIRING")) {
            String metric = ev.getMetric();
            double actual = metricValueOf(metric);
            int threshold = ev.getThreshold() == null ? 0 : ev.getThreshold();
            boolean recovered = "online_count".equals(metric) ? actual >= threshold : actual <= threshold;
            if (recovered) {
                ev.setStatus("RESOLVED");
                ev.setResolvedAt(now);
                ev.setResolutionNote("指标回落，自动恢复");
                alertEventRepository.save(ev);
            }
        }
    }

    private double metricValueOf(String metric) {
        String m = metric == null ? "" : metric.toLowerCase();
        Instant from = Instant.now().minus(1, ChronoUnit.MINUTES);
        return switch (m) {
            case "redscreen_rate" -> redscreenRepository.countByOccurredAtBetween(from, Instant.now());
            case "detection_rate" -> eventRepository.countByOccurredAtBetween(from, Instant.now());
            case "pending_inspect" -> inspectRepository.findAll().stream()
                    .filter(s -> "QUEUED".equals(s.getState())).count();
            case "online_count" -> onlineStatusService.onlineCount();
            default -> 0.0;
        };
    }

    @Transactional
    public AlertEvent acknowledge(String id, String operator) {
        AlertEvent ev = alertEventRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("告警事件不存在"));
        if (!"RESOLVED".equals(ev.getStatus())) {
            ev.setStatus("ACKNOWLEDGED");
            ev.setAcknowledgedAt(Instant.now());
            ev.setAcknowledgedBy(operator == null ? "api-key" : operator);
            alertEventRepository.save(ev);
        }
        return ev;
    }

    @Transactional
    public AlertEvent resolve(String id, String note, String operator) {
        AlertEvent ev = alertEventRepository.findById(id).orElseThrow(() -> new IllegalArgumentException("告警事件不存在"));
        ev.setStatus("RESOLVED");
        ev.setResolvedAt(Instant.now());
        if (note != null && !note.isBlank()) ev.setResolutionNote(note);
        if (operator == null) operator = "api-key";
        if (ev.getAcknowledgedBy() == null) ev.setAcknowledgedBy(operator);
        alertEventRepository.save(ev);
        return ev;
    }

    public List<AlertEvent> listEvents(String status, int limit) {
        List<AlertEvent> list = (status == null || status.isBlank())
                ? alertEventRepository.findTop50ByOrderByFiredAtDesc()
                : alertEventRepository.findByStatusOrderByFiredAtDesc(status);
        return list.stream().limit(Math.max(1, Math.min(limit, 200))).collect(Collectors.toList());
    }

    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("firing", alertEventRepository.countByStatus("FIRING"));
        out.put("acknowledged", alertEventRepository.countByStatus("ACKNOWLEDGED"));
        out.put("resolved", alertEventRepository.countByStatus("RESOLVED"));
        out.put("total", alertEventRepository.count());
        return out;
    }

    private int severityOf(String scope) {
        return switch (scope == null ? "" : scope.toUpperCase()) {
            case "QUEUE" -> 2;
            case "REALTIME" -> 1;
            default -> 1;
        };
    }

    private long cooldownSec(AlertRule rule) {
        int min = rule.getCooldownMin() == null ? 5 : rule.getCooldownMin();
        return Math.max(1, min) * 60L;
    }

    private String suppressKey(AlertRule rule, int severity) {
        return rule.getId() + ":" + severity;
    }
}