package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.AbExperiment;
import com.potatotv.pacc.repository.AbExperimentRepository;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A/B 测试框架确定性单测：分桶可复现与二分界、显著性 p 值范围/显著判断、生命周期 finish→publish。
 */
class AbExperimentServiceTest {

    private AbExperimentRepository repo;
    private AbExperimentService service;

    @BeforeEach
    void setUp() {
        repo = mock(AbExperimentRepository.class);
        service = new AbExperimentService(repo);
    }

    @Test
    void bucketIsDeterministicAndRespectsTargetBoundary() {
        AbExperiment exp = AbExperiment.builder()
                .id("exp_1").targetPercent(20)
                .variantA("CONTROL").variantB("TREATMENT")
                .build();
        when(repo.findById("exp_1")).thenReturn(Optional.of(exp));

        String[] pteids = {"PT1", "PT2", "PT3", "PT99", "PT42", "PT007"};
        for (String pteid : pteids) {
            int slot = service.slot(pteid, "exp_1");
            assertTrue(slot >= 0 && slot <= 99, "slot 应落在 [0,99]: " + slot);
            // 可复现：同一输入两次结果一致
            assertEquals(service.bucket(pteid, "exp_1"), service.bucket(pteid, "exp_1"));
            // 二分界一致：slot < targetPercent 归实验组，否则对照组
            String expected = slot < exp.getTargetPercent() ? exp.getVariantB() : exp.getVariantA();
            assertEquals(expected, service.bucket(pteid, "exp_1"));
        }
    }

    @Test
    void slotSpreadsWithinRangeAcrossInputs() {
        for (int i = 0; i < 200; i++) {
            int s = service.slot("PT" + i, "exp_stable");
            assertTrue(s >= 0 && s <= 99, "slot 越界: " + s);
        }
    }

    @Test
    void significanceReturnsPValueInRangeAndConsistentCTR() {
        AbExperiment exp = AbExperiment.builder()
                .id("exp_sig").metricsCtExposure(1000L).metricsCtDetect(100L).build();

        Map<String, Object> sig = service.significance(exp);

        double ctrA = (Double) sig.get("ctr_a");
        double ctrB = (Double) sig.get("ctr_b");
        double pValue = (Double) sig.get("p_value");
        // 半劈拆分口径下两组 CTR 相同，p 值应在 [0,1] 内，显著判定为布尔
        assertEquals(ctrA, ctrB);
        assertTrue(pValue >= 0.0 && pValue <= 1.0, "p 值越界: " + pValue);
        assertTrue(sig.get("significant") instanceof Boolean);
    }

    @Test
    void significanceWithZeroExposureIsNonSignificant() {
        AbExperiment exp = AbExperiment.builder().id("exp_zero").build();
        Map<String, Object> sig = service.significance(exp);
        assertEquals(0.0, (Double) sig.get("ctr_a"));
        assertEquals(0.0, (Double) sig.get("ctr_b"));
        assertEquals(1.0, (Double) sig.get("p_value"));
        assertEquals(Boolean.FALSE, sig.get("significant"));
    }

    @Test
    void finishThenPublishSucceeds() {
        AbExperiment running = AbExperiment.builder()
                .id("exp_lc").status("RUNNING").variantA("A").variantB("B")
                .metricsCtExposure(100L).metricsCtDetect(20L).build();
        when(repo.findById("exp_lc")).thenReturn(Optional.of(running));
        when(repo.save(any(AbExperiment.class))).thenAnswer(inv -> inv.getArgument(0));

        AbExperiment finished = service.finish("exp_lc");
        assertEquals("FINISHED", finished.getStatus());
        assertNotNull(finished.getEndedAt());

        AbExperiment archived = service.publish("exp_lc");
        assertEquals("ARCHIVED", archived.getStatus());
        assertNotNull(archived.getWinner());
    }

    @Test
    void publishOnNonFinishedThrows() {
        AbExperiment running = AbExperiment.builder().id("exp_x").status("RUNNING").build();
        when(repo.findById("exp_x")).thenReturn(Optional.of(running));

        assertThrows(IllegalStateException.class, () -> service.publish("exp_x"));
    }
}