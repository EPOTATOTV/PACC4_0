package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.V52ReputationLogRepository;
import com.potatotv.pacc.service.detection.v52.ReputationV2Service.ReputationPolicy;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §7.2 信誉等级 → 检测策略纯函数单测：五个等级的阈值倍率与行为开关，且边界精确。
 */
class ReputationPolicyTest {

    @Test
    void trustedLevelUsesL0OnlyAndLenientThreshold() {
        ReputationPolicy p = ReputationV2Service.policy(950);
        assertEquals(20, p.thresholdPercentDelta());
        assertEquals(1.20, p.thresholdMultiplier(), 1e-9);
        assertTrue(p.l0Only());
        assertFalse(p.forceL2());
        assertFalse(p.fullFeatureReport());
    }

    @Test
    void normalLevelKeepsBaselineThreshold() {
        ReputationPolicy p = ReputationV2Service.policy(800);
        assertEquals(0, p.thresholdPercentDelta());
        assertEquals(1.0, p.thresholdMultiplier(), 1e-9);
        assertFalse(p.l0Only());
        assertFalse(p.forceL2());
        assertFalse(p.fullFeatureReport());
    }

    @Test
    void observedLevelTightensByTenPercent() {
        ReputationPolicy p = ReputationV2Service.policy(600);
        assertEquals(-10, p.thresholdPercentDelta());
        assertEquals(0.9, p.thresholdMultiplier(), 1e-9);
        assertFalse(p.l0Only());
    }

    @Test
    void riskLevelTightensByTwentyPercentAndForcesL2() {
        ReputationPolicy p = ReputationV2Service.policy(400);
        assertEquals(-20, p.thresholdPercentDelta());
        assertEquals(0.8, p.thresholdMultiplier(), 1e-9);
        assertTrue(p.forceL2());
        assertFalse(p.fullFeatureReport());
    }

    @Test
    void highRiskLevelTightensByThirtyPercentAndReportsFullFeatures() {
        ReputationPolicy p = ReputationV2Service.policy(100);
        assertEquals(-30, p.thresholdPercentDelta());
        assertEquals(0.7, p.thresholdMultiplier(), 1e-9);
        assertTrue(p.forceL2());
        assertTrue(p.fullFeatureReport());
    }

    @Test
    void policyBoundariesAreExact() {
        assertEquals(PlayerBehaviorProfile.LEVEL_HIGH_RISK, ReputationV2Service.levelFor(299));
        assertEquals(-30, ReputationV2Service.policy(299).thresholdPercentDelta());
        assertEquals(-20, ReputationV2Service.policy(300).thresholdPercentDelta());
        assertEquals(-20, ReputationV2Service.policy(499).thresholdPercentDelta());
        assertEquals(-10, ReputationV2Service.policy(500).thresholdPercentDelta());
        assertEquals(-10, ReputationV2Service.policy(699).thresholdPercentDelta());
        assertEquals(0, ReputationV2Service.policy(700).thresholdPercentDelta());
        assertEquals(0, ReputationV2Service.policy(899).thresholdPercentDelta());
        assertEquals(20, ReputationV2Service.policy(900).thresholdPercentDelta());
        assertEquals(20, ReputationV2Service.policy(1000).thresholdPercentDelta());
        // 越界分值先裁剪再判级
        assertEquals(20, ReputationV2Service.policy(5000).thresholdPercentDelta());
        assertEquals(-30, ReputationV2Service.policy(-5).thresholdPercentDelta());
    }

    @Test
    void policyByPteidMatchesPolicyOfScore() {
        PlayerBehaviorProfileRepository profileRepo = mock(PlayerBehaviorProfileRepository.class);
        V52ReputationLogRepository logRepo = mock(V52ReputationLogRepository.class);
        ReputationV2Service service = new ReputationV2Service(profileRepo, logRepo);

        when(profileRepo.findById(eq("PT1"))).thenReturn(Optional.of(PlayerBehaviorProfile.builder()
                .pteid("PT1")
                .reputationScore(950)
                .reputationLevel(PlayerBehaviorProfile.LEVEL_TRUSTED)
                .build()));
        when(profileRepo.findById(eq("PT-none"))).thenReturn(Optional.empty());

        assertEquals(ReputationV2Service.policy(950), service.policy("PT1"));
        assertEquals(ReputationV2Service.policy(ReputationV2Service.INITIAL_SCORE), service.policy("PT-none"));
    }
}