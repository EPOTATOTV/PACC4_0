package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.potatotv.pacc.domain.ConfidenceTier;
import com.potatotv.pacc.service.ScanIntensityService.ScanProfile;
import org.junit.jupiter.api.Test;

/**
 * 扫描强度自适应确定性单测：分级映射、红屏复查、场景频率缩放。
 */
class ScanIntensityServiceTest {

    private final ScanIntensityService service = new ScanIntensityService();

    @Test
    void lowTierUsesSnapshotLowFrequency() {
        ScanProfile p = service.profile(ConfidenceTier.LOW, false, ScanIntensityService.Scenario.NORMAL);
        assertEquals("SNAPSHOT", p.mode());
        assertEquals(1, p.depth());
        assertTrue(p.intervalMs() >= 30000);
    }

    @Test
    void mediumTierUsesIncrementalEnhanced() {
        ScanProfile p = service.profile(ConfidenceTier.MEDIUM, false, ScanIntensityService.Scenario.NORMAL);
        assertEquals("INCREMENTAL", p.mode());
        assertEquals(2, p.depth());
        assertEquals(10000, p.intervalMs());
    }

    @Test
    void highTierUsesFullDeepScan() {
        ScanProfile p = service.profile(ConfidenceTier.HIGH, false, ScanIntensityService.Scenario.NORMAL);
        assertEquals("FULL", p.mode());
        assertEquals(3, p.depth());
        assertEquals(5000, p.intervalMs());
    }

    @Test
    void recentRedscreenForcesFullRecheck() {
        ScanProfile p = service.profile(ConfidenceTier.LOW, true, ScanIntensityService.Scenario.MENU);
        assertEquals("FULL", p.mode());
        assertEquals(3, p.depth());
    }

    @Test
    void combatScalesIntervalDownMenuScalesUp() {
        long combat = service.profile(ConfidenceTier.LOW, false, ScanIntensityService.Scenario.COMBAT).intervalMs();
        long menu = service.profile(ConfidenceTier.LOW, false, ScanIntensityService.Scenario.MENU).intervalMs();
        assertTrue(combat < menu, "战斗应以更高频率（更短间隔）扫描");
    }

    @Test
    void intervalNeverBelowFloor() {
        ScanProfile p = service.profile(ConfidenceTier.HIGH, true, ScanIntensityService.Scenario.COMBAT);
        assertTrue(p.intervalMs() >= 2000);
    }
}