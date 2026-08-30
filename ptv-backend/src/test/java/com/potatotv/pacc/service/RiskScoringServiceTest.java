package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.rule.LuaRuleEngine;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 风险评分确定性单测：
 * 维度权重、历史信誉加成、critical 抬升、AI 融合、规则加分、0-100 封顶均为精确断言。
 */
class RiskScoringServiceTest {

    private AiInferenceClient ai;
    private LuaRuleEngine lua;
    private RiskScoringService service;

    @BeforeEach
    void setUp() {
        ai = mock(AiInferenceClient.class);
        lua = mock(LuaRuleEngine.class);
        // AI 默认不启用（返回空）；规则默认无加分
        when(ai.score(any(), any())).thenReturn(Optional.empty());
        when(lua.evaluate(anyMap())).thenReturn(new LuaRuleEngine.Evaluation(List.of(), 0.0, 0));
        service = new RiskScoringService(ai, lua);
    }

    private DetectionEvent event(String type, String severity, int risk) {
        return DetectionEvent.builder()
                .pteid("PT42")
                .eventType(type)
                .severity(severity)
                .edition(DetectionEvent.Edition.JAVA)
                .clientRiskScore(risk)
                .build();
    }

    private Account account(int reputation) {
        return Account.builder().reputation(reputation).build();
    }

    @Test
    void lowLevelWeightAndCleanReputation() {
        // memory_tamper 走底层维度 0.95 权重；信誉 100 → 历史加成 0 → 80*0.95=76
        assertEquals(76, service.score(event("memory_tamper", "medium", 80), account(100)));
    }

    @Test
    void criticalSeverityBoostsTenPoints() {
        // critical 抬升 +10 → 76+10=86
        assertEquals(86, service.score(event("memory_tamper", "critical", 80), account(100)));
    }

    @Test
    void behaviorWeight() {
        // killaura 行为维度 0.8 权重 → 100*0.8=80
        assertEquals(80, service.score(event("killaura", "medium", 100), account(100)));
    }

    @Test
    void ruleBonusAdded() {
        when(lua.evaluate(anyMap()))
                .thenReturn(new LuaRuleEngine.Evaluation(
                        List.of(new LuaRuleEngine.RuleHit("mem", "内存", 5, "")), 5.0, 1));
        assertEquals(85, service.score(event("killaura", "medium", 100), account(100)));
    }

    @Test
    void aiFusionWeighted() {
        // 本地分 76，AI 分 100，15% 融合 → 76*0.85+100*0.15=79.6 → 79
        when(ai.score(any(), any())).thenReturn(Optional.of(100.0));
        assertEquals(79, service.score(event("memory_tamper", "medium", 80), account(100)));
    }

    @Test
    void poorReputationRaisesScore() {
        // 信誉 0 → historyFactor 1.0 → 76 + 12 = 88
        assertEquals(88, service.score(event("memory_tamper", "medium", 80), account(0)));
    }

    @Test
    void scoreClampedAt100() {
        // 95(底层满) + 12(信誉加成) + 10(critical) = 117 → 封顶 100
        assertEquals(100, service.score(event("memory_tamper", "critical", 100), account(0)));
    }

    @Test
    void envWeightLowest() {
        // debugger 环境维度 0.6 权重 → 100*0.6=60
        assertEquals(60, service.score(event("debugger", "medium", 100), account(100)));
    }
}