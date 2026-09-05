package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.potatotv.pacc.domain.ConfidenceTier;
import org.junit.jupiter.api.Test;

/**
 * 置信度三级分级确定性单测：阈值边界。
 */
class ConfidenceServiceTest {

    private final ConfidenceService service = new ConfidenceService(70, 85);

    @Test
    void enumBoundary() {
        assertEquals(ConfidenceTier.LOW, ConfidenceTier.of(0, 70, 85));
        assertEquals(ConfidenceTier.LOW, ConfidenceTier.of(69, 70, 85));
        assertEquals(ConfidenceTier.MEDIUM, ConfidenceTier.of(70, 70, 85));
        assertEquals(ConfidenceTier.MEDIUM, ConfidenceTier.of(84, 70, 85));
        assertEquals(ConfidenceTier.HIGH, ConfidenceTier.of(85, 70, 85));
        assertEquals(ConfidenceTier.HIGH, ConfidenceTier.of(100, 70, 85));
    }

    @Test
    void classifyUsesConfiguredThresholds() {
        assertEquals(ConfidenceTier.LOW, service.classify(50));
        assertEquals(ConfidenceTier.MEDIUM, service.classify(70));
        assertEquals(ConfidenceTier.MEDIUM, service.classify(84));
        assertEquals(ConfidenceTier.HIGH, service.classify(85));
        assertEquals(ConfidenceTier.HIGH, service.classify(100));
    }

    @Test
    void exposeThresholds() {
        assertEquals(70, service.mediumThreshold());
        assertEquals(85, service.highThreshold());
    }
}