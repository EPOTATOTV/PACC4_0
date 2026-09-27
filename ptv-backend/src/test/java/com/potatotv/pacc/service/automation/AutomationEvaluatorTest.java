package com.potatotv.pacc.service.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.df.DfProperties;
import com.potatotv.pacc.domain.alert.AlertGroup;
import com.potatotv.pacc.domain.automation.AutomationExecution;
import com.potatotv.pacc.domain.automation.AutomationRule;
import com.potatotv.pacc.repository.AlertGroupRepository;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.AutomationExecutionRepository;
import com.potatotv.pacc.repository.AutomationRuleRepository;
import com.potatotv.pacc.repository.ClientCrashReportRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.IocIndicatorRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §4.3.3 评估器单测：命中阈值即执行动作并落审计；停用规则为 no-op；冷却期内不重复触发。
 */
class AutomationEvaluatorTest {

    private AutomationRuleRepository ruleRepository;
    private AutomationExecutionRepository executionRepository;
    private AutomationActionRegistry registry;
    private AlertGroupRepository alertGroupRepository;
    private AutomationEvaluator evaluator;

    @BeforeEach
    void setUp() {
        ruleRepository = mock(AutomationRuleRepository.class);
        executionRepository = mock(AutomationExecutionRepository.class);
        registry = mock(AutomationActionRegistry.class);
        alertGroupRepository = mock(AlertGroupRepository.class);
        when(executionRepository.save(any(AutomationExecution.class))).thenAnswer(inv -> inv.getArgument(0));
        when(ruleRepository.save(any(AutomationRule.class))).thenAnswer(inv -> inv.getArgument(0));
        evaluator = new AutomationEvaluator(ruleRepository, executionRepository, registry, new DfProperties(),
                mock(SystemLoadProvider.class), alertGroupRepository, mock(IocIndicatorRepository.class),
                mock(AppealRepository.class), mock(ClientCrashReportRepository.class),
                mock(DetectionEventRepository.class));
    }

    @Test
    void firedRuleExecutesActionAndWritesAudit() {
        AutomationRule rule = familyBurstRule(true, null);
        when(ruleRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(rule));
        when(alertGroupRepository.findByLastSeenAtAfter(any(Instant.class)))
                .thenReturn(List.of(AlertGroup.builder().id("g1").familyCode("fam-x").signalCount(150).build()));

        AutomationActionHandler handler = mock(AutomationActionHandler.class);
        when(handler.execute(any(AutomationContext.class)))
                .thenReturn(AutomationActionResult.applied("sensitivity 1.0->1.5"));
        when(registry.find("ESCALATE_SENSITIVITY")).thenReturn(Optional.of(handler));

        List<AutomationExecution> results = evaluator.evaluate("scheduler");

        assertEquals(1, results.size());
        assertEquals(AutomationExecution.ST_SUCCESS, results.get(0).getStatus());
        assertEquals("ESCALATE_SENSITIVITY", results.get(0).getActionCode());
        assertNotNull(rule.getLastFiredAt(), "命中后应记录 lastFiredAt");
        verify(executionRepository).save(any(AutomationExecution.class));
    }

    @Test
    void disabledRuleIsNoOp() {
        AutomationRule rule = familyBurstRule(false, null);
        when(ruleRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(rule));

        assertTrue(evaluator.evaluate("scheduler").isEmpty());
        verify(executionRepository, never()).save(any(AutomationExecution.class));
        verify(ruleRepository, never()).save(any(AutomationRule.class));
    }

    @Test
    void cooldownSuppressesRepeatExecution() {
        AutomationRule rule = familyBurstRule(true, Instant.now());
        when(ruleRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(rule));

        assertTrue(evaluator.evaluate("scheduler").isEmpty());
        verify(executionRepository, never()).save(any(AutomationExecution.class));
    }

    @Test
    void noopHandlerRecordsSkippedWithoutTouchingLastFired() {
        AutomationRule rule = familyBurstRule(true, null);
        when(ruleRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(rule));
        when(alertGroupRepository.findByLastSeenAtAfter(any(Instant.class)))
                .thenReturn(List.of(AlertGroup.builder().id("g1").familyCode("fam-x").signalCount(150).build()));

        AutomationActionHandler handler = mock(AutomationActionHandler.class);
        when(handler.execute(any(AutomationContext.class)))
                .thenReturn(AutomationActionResult.noop("sensitivity 已达上限"));
        when(registry.find("ESCALATE_SENSITIVITY")).thenReturn(Optional.of(handler));

        List<AutomationExecution> results = evaluator.evaluate("scheduler");

        assertEquals(1, results.size());
        assertEquals(AutomationExecution.ST_SKIPPED, results.get(0).getStatus());
        assertNull(rule.getLastFiredAt());
        verify(ruleRepository, never()).save(any(AutomationRule.class));
    }

    @Test
    void unregisteredActionIsSkippedNotFailed() {
        AutomationRule rule = familyBurstRule(true, null);
        when(ruleRepository.findAllByOrderByCodeAsc()).thenReturn(List.of(rule));
        when(alertGroupRepository.findByLastSeenAtAfter(any(Instant.class)))
                .thenReturn(List.of(AlertGroup.builder().id("g1").familyCode("fam-x").signalCount(150).build()));
        when(registry.find(anyString())).thenReturn(Optional.empty());

        List<AutomationExecution> results = evaluator.evaluate("scheduler");

        assertEquals(1, results.size());
        assertEquals(AutomationExecution.ST_SKIPPED, results.get(0).getStatus());
    }

    private static AutomationRule familyBurstRule(boolean enabled, Instant lastFiredAt) {
        return AutomationRule.builder()
                .id("rule_family_detection_burst")
                .code(AutomationEvaluator.TRIGGER_FAMILY_BURST)
                .name("作弊家族检测风暴")
                .triggerExpr("family detection count >= 100 / 1h")
                .actionCode("ESCALATE_SENSITIVITY")
                .threshold(100.0)
                .windowMin(60)
                .cooldownMin(30)
                .enabled(enabled)
                .builtin(true)
                .lastFiredAt(lastFiredAt)
                .build();
    }
}