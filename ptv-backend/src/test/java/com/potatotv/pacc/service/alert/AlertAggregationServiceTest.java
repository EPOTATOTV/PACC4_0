package com.potatotv.pacc.service.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.df.DfProperties;
import com.potatotv.pacc.domain.alert.AlertGroup;
import com.potatotv.pacc.repository.AlertGroupRepository;
import com.potatotv.pacc.repository.AlertNotifyQueueRepository;
import com.potatotv.pacc.repository.AlertSuppressionRuleRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §4.3.2 降噪度量单测：验证验收 A23「≥60% 告警被聚合」。
 *
 * <p>两条路径都覆盖：纯函数聚合（{@link AlertAggregationService#aggregate}）
 * 与持久化统计（{@link AlertAggregationService#noiseStats}），降噪率口径均为
 * {@code (raw - aggregated) / raw}。</p>
 */
class AlertAggregationServiceTest {

    private AlertGroupRepository groupRepository;
    private AlertSuppressionRuleRepository suppressionRuleRepository;
    private AlertAggregationService service;

    @BeforeEach
    void setUp() {
        groupRepository = mock(AlertGroupRepository.class);
        suppressionRuleRepository = mock(AlertSuppressionRuleRepository.class);
        AlertNotifyQueueRepository queueRepository = mock(AlertNotifyQueueRepository.class);
        AlertSuppressionService suppressionService = mock(AlertSuppressionService.class);
        service = new AlertAggregationService(groupRepository, queueRepository, suppressionRuleRepository,
                suppressionService, new AlertPriorityService(), new DfProperties());
    }

    @Test
    void aggregateMergesSamePlayerAlertsAndMeetsA23() {
        // 同一玩家 100 条 + 同一作弊家族 50 条 → 原始 150 条，聚合为 2 组
        List<AlertSignal> signals = new ArrayList<>();
        Instant now = Instant.now();
        for (int i = 0; i < 100; i++) {
            signals.add(signal("PTEID0001", null, "rule-a", now));
        }
        for (int i = 0; i < 50; i++) {
            signals.add(signal(null, "family-x", "rule-b", now));
        }

        List<AlertAggregationService.AlertGroupDraft> drafts = service.aggregate(signals);

        long raw = signals.size();
        long aggregated = drafts.size();
        double reductionRate = (raw - aggregated) / (double) raw;

        assertEquals(2, aggregated, "同一玩家 / 同一家族各自应合并为 1 组");
        assertEquals(100, drafts.stream().mapToInt(AlertAggregationService.AlertGroupDraft::signalCount)
                .max().orElse(0));
        assertTrue(reductionRate >= 0.60, "A23 降噪率应 >= 60%，实际 = " + reductionRate);
    }

    @Test
    void noiseStatsExposesReductionRateAboveThreshold() {
        Instant now = Instant.now();
        when(groupRepository.findByLastSeenAtAfter(any(Instant.class))).thenReturn(List.of(
                group("g1", 30, now), group("g2", 30, now)));
        when(suppressionRuleRepository.findByEnabledTrue()).thenReturn(List.of());

        Map<String, Object> stats = service.noiseStats(24);

        assertEquals(60L, stats.get("rawCount"));
        assertEquals(2L, stats.get("aggregatedCount"));
        assertEquals(0.9667, (Double) stats.get("reductionRate"), 0.0001);
        assertTrue((Double) stats.get("reductionRate") >= 0.60);
    }

    private static AlertSignal signal(String playerId, String familyCode, String ruleId, Instant at) {
        return AlertSignal.builder()
                .alertId("ae-" + ruleId + "-" + at.toEpochMilli())
                .playerId(playerId)
                .familyCode(familyCode)
                .ruleId(ruleId)
                .ruleName(ruleId)
                .metric("m")
                .severity(3)
                .occurredAt(at)
                .build();
    }

    private static AlertGroup group(String id, int signalCount, Instant lastSeenAt) {
        return AlertGroup.builder()
                .id(id)
                .signalCount(signalCount)
                .severity(3)
                .priority("P1")
                .lastSeenAt(lastSeenAt)
                .firstSeenAt(lastSeenAt)
                .build();
    }
}