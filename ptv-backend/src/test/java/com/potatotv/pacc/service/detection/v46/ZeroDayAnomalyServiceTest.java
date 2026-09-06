package com.potatotv.pacc.service.detection.v46;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import com.potatotv.pacc.service.ConfidenceService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v4.6 零日外挂检测单测：作弊特征综合分显著高于人类基线，且落入置信分级、落库为发现。
 */
class ZeroDayAnomalyServiceTest {

    private ZeroDayAnomalyService service;

    @BeforeEach
    void setUp() {
        ZeroDayFindingRepository repo = mock(ZeroDayFindingRepository.class);
        when(repo.save(any(ZeroDayFinding.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new ZeroDayAnomalyService(repo, new ConfidenceService(70, 85),
                0.5, 0.3, 0.2, 1.2, 3.0);
    }

    private static FeatureVector human() {
        return new FeatureVector()
                .set("feature_killaura_angle_speed", 12.0)
                .set("feature_aim_smoothness", 0.4)
                .set("feature_click_interval_cv", 0.3)
                .set("feature_semantic_killaura", 0.05)
                .set("feature_speed_ratio", 1.0)
                .set("feature_human_likeness", 0.85)
                .set("feature_trajectory_curvature", 0.55)
                .set("feature_jitter_entropy", 3.2);
    }

    private static FeatureVector cheat() {
        return new FeatureVector()
                .set("feature_killaura_angle_speed", 62.0)
                .set("feature_aim_smoothness", 0.02)
                .set("feature_click_interval_cv", 0.03)
                .set("feature_semantic_killaura", 0.82)
                .set("feature_speed_ratio", 1.9)
                .set("feature_human_likeness", 0.12)
                .set("feature_trajectory_curvature", 0.98)
                .set("feature_jitter_entropy", 0.4);
    }

    @Test
    void cheatCompositeExceedsHuman() {
        Map<String, Object> human = service.assess("PT1", "JAVA", human());
        Map<String, Object> cheat = service.assess("PT2", "JAVA", cheat());

        int h = (Integer) human.get("composite");
        int c = (Integer) cheat.get("composite");
        assertTrue(c > h, "cheat composite " + c + " should exceed human " + h);
        assertTrue(c <= 100 && c >= 0);
    }

    @Test
    void reportContainsTierAndFindingId() {
        Map<String, Object> r = service.assess("PT9", "BEDROCK", cheat());
        assertNotNull(r.get("finding_id"));
        assertTrue(r.get("confidence_tier") instanceof String);
        assertTrue(r.get("iso_score") instanceof Double);
        assertEquals("BEDROCK", r.get("edition"));
    }

    @Test
    void cheatLandsHighOrAtLeastMediumTier() {
        Map<String, Object> cheat = service.assess("PT3", "JAVA", cheat());
        String tier = (String) cheat.get("confidence_tier");
        assertTrue(tier.equals("HIGH") || tier.equals("MEDIUM"), "unexpected tier " + tier);
    }
}