package com.potatotv.pacc.service.automation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.automation.AutomationExecution;
import com.potatotv.pacc.domain.automation.AutomationState;
import com.potatotv.pacc.domain.automation.AutomationRule;
import com.potatotv.pacc.repository.AutomationStateRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * §4.3.3 动作④单测：负载过高时必须落到「其他服务真实读取的开关」，
 * 而不是只打日志；且回滚可依据执行明细还原前值。
 */
class DegradeDetectionsHandlerTest {

    private final Map<String, AutomationState> store = new HashMap<>();
    private AutomationStateService stateService;
    private DegradeDetectionsHandler handler;

    @BeforeEach
    void setUp() {
        AutomationStateRepository repository = mock(AutomationStateRepository.class);
        when(repository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.getArgument(0, String.class))));
        when(repository.save(any(AutomationState.class))).thenAnswer(inv -> {
            AutomationState saved = inv.getArgument(0);
            store.put(saved.getStateKey(), saved);
            return saved;
        });
        stateService = new AutomationStateService(repository);
        handler = new DegradeDetectionsHandler(stateService);
    }

    @Test
    void executeFlipsRealSwitchesAndRevertRestoresThem() {
        AutomationRule rule = AutomationRule.builder()
                .id("rule_backend_load_high")
                .code(AutomationEvaluator.TRIGGER_BACKEND_LOAD)
                .name("后端负载过高")
                .triggerExpr("backend cpu load > 80%")
                .actionCode("DEGRADE_DETECTIONS")
                .threshold(80.0)
                .windowMin(15)
                .cooldownMin(15)
                .build();
        AutomationContext context = new AutomationContext(rule, Map.of("load", 92.5), "scheduler");

        AutomationActionResult result = handler.execute(context);

        // 真实开关：其他服务读取到的必须是降级态
        assertEquals(true, result.applied());
        assertEquals("true", value(AutomationStateService.KEY_DEGRADED_MODE));
        assertEquals("false", value(AutomationStateService.KEY_NON_CRITICAL_DETECTION));
        assertEquals("2.0", value(AutomationStateService.KEY_REPORT_INTERVAL));
        assertEquals(true, stateService.isDegradedMode());
        assertEquals(false, stateService.isNonCriticalDetectionEnabled());
        assertEquals(2.0, stateService.reportIntervalMultiplier(), 0.0001);

        AutomationExecution execution = AutomationExecution.builder()
                .ruleCode(rule.getCode())
                .actionCode(rule.getActionCode())
                .detail(result.detail())
                .build();
        AutomationActionResult reverted = handler.revert(execution);

        assertEquals(true, reverted.applied());
        assertEquals("false", value(AutomationStateService.KEY_DEGRADED_MODE));
        assertEquals("true", value(AutomationStateService.KEY_NON_CRITICAL_DETECTION));
        assertEquals("1.0", value(AutomationStateService.KEY_REPORT_INTERVAL));
    }

    private String value(String key) {
        AutomationState state = store.get(key);
        return state == null ? null : state.getStateValue();
    }
}