package com.potatotv.pacc.service.alert;

import com.potatotv.pacc.domain.alert.AlertGroup;
import com.potatotv.pacc.domain.alert.AlertNotifyQueue;
import com.potatotv.pacc.domain.alert.AlertSuppressionRule;
import com.potatotv.pacc.df.DfProperties;
import com.potatotv.pacc.repository.AlertGroupRepository;
import com.potatotv.pacc.repository.AlertNotifyQueueRepository;
import com.potatotv.pacc.repository.AlertSuppressionRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * §4.3.2 告警聚合：降噪流水线第一段——把原始告警流合并为告警组。
 *
 * <p>规则：同一玩家的多个告警合并为 1 条；同一作弊家族的多个告警聚合为一个事件；
 * 已知误报模式先经 {@link AlertSuppressionService} 抑制；聚合后按 {@link AlertPriorityService}
 * 排序并写入 {@code t_alert_notify_queue}（低优先级延迟批量、高优先级即时）。</p>
 *
 * <p>降噪率 {@code (raw - aggregated) / raw} 由 {@link #noiseStats(int)} 暴露，
 * 用于验收 A23（≥60% 告警被聚合）的量化验证。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储/流式聚合 null 分析误报
public class AlertAggregationService {

    private final AlertGroupRepository groupRepository;
    private final AlertNotifyQueueRepository queueRepository;
    private final AlertSuppressionRuleRepository suppressionRuleRepository;
    private final AlertSuppressionService suppressionService;
    private final AlertPriorityService priorityService;
    private final DfProperties props;

    /** 聚合草案（纯计算结果，不落库）。 */
    public record AlertGroupDraft(String groupKey, String tenantId, String playerId, String familyCode,
                                  String ruleId, int severity, int signalCount, String priority,
                                  Instant firstSeenAt, Instant lastSeenAt) { }

    /**
     * 纯函数聚合：把信号列表按 {@link AlertSignal#groupKey()} 合并为草案。
     * <p>便于单测与离线度量（A23），不产生任何副作用。</p>
     */
    public List<AlertGroupDraft> aggregate(List<AlertSignal> signals) {
        Map<String, List<AlertSignal>> buckets = new LinkedHashMap<>();
        for (AlertSignal s : signals) {
            buckets.computeIfAbsent(s.groupKey(), k -> new ArrayList<>()).add(s);
        }
        List<AlertGroupDraft> drafts = new ArrayList<>();
        for (Map.Entry<String, List<AlertSignal>> e : buckets.entrySet()) {
            List<AlertSignal> bucket = e.getValue();
            AlertSignal head = bucket.get(0);
            int severity = bucket.stream().mapToInt(AlertSignal::getSeverity).max().orElse(head.getSeverity());
            Instant first = bucket.stream().map(AlertSignal::getOccurredAt)
                    .min(Comparator.naturalOrder()).orElse(head.getOccurredAt());
            Instant last = bucket.stream().map(AlertSignal::getOccurredAt)
                    .max(Comparator.naturalOrder()).orElse(head.getOccurredAt());
            drafts.add(new AlertGroupDraft(e.getKey(), head.getTenantId(), head.getPlayerId(), head.getFamilyCode(),
                    head.getRuleId(), severity, bucket.size(),
                    priorityService.priorityOf(severity, bucket.size()), first, last));
        }
        drafts.sort(Comparator.comparing(AlertGroupDraft::priority));
        return drafts;
    }

    /**
     * 降噪入口：由 {@code AlertService} 触发告警后调用。
     * 抑制命中则直接丢弃；否则在聚合窗口内合并入既有组或新建组，并按优先级入队。
     *
     * @return 聚合组；被抑制时为空
     */
    @Transactional
    public Optional<AlertGroup> ingest(AlertSignal signal) {
        if (signal == null) {
            return Optional.empty();
        }
        if (suppressionService.isSuppressed(signal)) {
            return Optional.empty();
        }
        AlertGroup group = upsert(signal);
        enqueue(group);
        return Optional.of(group);
    }

    private AlertGroup upsert(AlertSignal signal) {
        Instant windowStart = Instant.now().minus(Duration.ofMinutes(
                Math.max(1, props.getAlert().getAggregationWindowMinutes())));
        AlertGroup matched = groupRepository.findByLastSeenAtAfter(windowStart).stream()
                .filter(g -> sameGroup(g, signal))
                .findFirst()
                .orElse(null);
        if (matched != null) {
            matched.setSignalCount(matched.getSignalCount() + 1);
            matched.setSeverity(Math.max(matched.getSeverity(), signal.getSeverity()));
            matched.setPriority(priorityService.priorityOf(matched.getSeverity(), matched.getSignalCount()));
            matched.setLastSeenAt(signal.getOccurredAt() == null ? Instant.now() : signal.getOccurredAt());
            matched.setRawAlertIds(appendId(matched.getRawAlertIds(), signal.getAlertId()));
            return groupRepository.save(matched);
        }
        AlertGroup group = AlertGroup.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .tenantId(signal.getTenantId())
                .playerId(signal.getPlayerId())
                .familyCode(signal.getFamilyCode())
                .ruleId(signal.getRuleId())
                .severity(signal.getSeverity())
                .priority(priorityService.priorityOf(signal.getSeverity(), 1))
                .signalCount(1)
                .rawAlertIds(appendId(null, signal.getAlertId()))
                .status(AlertGroup.ST_OPEN)
                .firstSeenAt(signal.getOccurredAt() == null ? Instant.now() : signal.getOccurredAt())
                .lastSeenAt(signal.getOccurredAt() == null ? Instant.now() : signal.getOccurredAt())
                .build();
        return groupRepository.save(group);
    }

    /** 入队通知：低优先级延迟批量，其余即时。 */
    private void enqueue(AlertGroup group) {
        boolean low = priorityService.isLowPriority(group.getPriority());
        Instant now = Instant.now();
        Instant scheduled = low
                ? now.plus(Duration.ofMinutes(Math.max(1, props.getAlert().getLowPriorityDelayMinutes())))
                : now;
        queueRepository.save(AlertNotifyQueue.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .groupId(group.getId())
                .priority(group.getPriority())
                .channel(low ? "dashboard-batch" : "dashboard")
                .status(low ? AlertNotifyQueue.ST_HELD : AlertNotifyQueue.ST_PENDING)
                .scheduledAt(scheduled)
                .payload("group=" + group.getId() + " player=" + group.getPlayerId()
                        + " family=" + group.getFamilyCode() + " signals=" + group.getSignalCount())
                .build());
    }

    private static boolean sameGroup(AlertGroup g, AlertSignal s) {
        if (g.getTenantId() != null && s.getTenantId() != null && !g.getTenantId().equals(s.getTenantId())) {
            return false;
        }
        if (s.getPlayerId() != null && !s.getPlayerId().isBlank()) {
            return s.getPlayerId().equals(g.getPlayerId());
        }
        if (s.getFamilyCode() != null && !s.getFamilyCode().isBlank()) {
            return g.getPlayerId() == null && s.getFamilyCode().equals(g.getFamilyCode());
        }
        return g.getPlayerId() == null && g.getFamilyCode() == null
                && java.util.Objects.equals(s.getRuleId(), g.getRuleId());
    }

    private static String appendId(String existing, String id) {
        if (id == null || id.isBlank()) {
            return existing;
        }
        String merged = existing == null || existing.isBlank() ? id : existing + "," + id;
        return merged.length() > 1990 ? merged.substring(merged.length() - 1990) : merged;
    }

    /**
     * 降噪统计：窗口内的聚合前后条数与降噪率。
     *
     * @param windowHours 统计窗口（小时），≤0 时取默认 24
     */
    @Transactional(readOnly = true)
    public Map<String, Object> noiseStats(int windowHours) {
        int hours = windowHours <= 0 ? 24 : Math.min(windowHours, 24 * 30);
        Instant since = Instant.now().minus(Duration.ofHours(hours));
        List<AlertGroup> groups = groupRepository.findByLastSeenAtAfter(since);
        long aggregated = groups.size();
        long raw = groups.stream().mapToLong(AlertGroup::getSignalCount).sum();
        long suppressed = suppressionRuleRepository.findByEnabledTrue().stream()
                .filter(r -> r.getLastHitAt() != null && r.getLastHitAt().isAfter(since))
                .mapToLong(AlertSuppressionRule::getHitCount).sum();
        double rate = raw == 0 ? 0.0 : (raw - aggregated) / (double) raw;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowHours", hours);
        out.put("rawCount", raw);
        out.put("aggregatedCount", aggregated);
        out.put("suppressedCount", suppressed);
        out.put("reductionRate", Math.round(rate * 10_000.0) / 10_000.0);
        return out;
    }

    /** 聚合告警组分页。 */
    @Transactional(readOnly = true)
    public Map<String, Object> groups(int page, int size) {
        PageRequest pg = PageRequest.of(Math.max(0, page), Math.min(100, Math.max(1, size)),
                Sort.by(Sort.Direction.DESC, "lastSeenAt"));
        Page<AlertGroup> data = groupRepository.findAllByOrderByLastSeenAtDesc(pg);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", data.getContent());
        out.put("total", data.getTotalElements());
        out.put("page", data.getNumber());
        out.put("totalPages", data.getTotalPages());
        return out;
    }
}