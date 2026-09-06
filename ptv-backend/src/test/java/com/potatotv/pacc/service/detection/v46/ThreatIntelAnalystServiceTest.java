package com.potatotv.pacc.service.detection.v46;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * v4.6 威胁情报自动分析单测：指标/类型/严重度判定与家族聚类赋族。
 */
class ThreatIntelAnalystServiceTest {

    private final ThreatIntelSampleRepository repo = mock(ThreatIntelSampleRepository.class);
    private final ThreatIntelAnalystService service =
            new ThreatIntelAnalystService(repo, new SignatureExpansionService(), new ObjectMapper());

    @Test
    void analyzeGhostClientDetectsTypeAndSeeds() {
        when(repo.save(any(ThreatIntelSample.class))).thenAnswer(inv -> inv.getArgument(0));
        ThreatIntelSample s = ThreatIntelSample.builder()
                .id("s1").pteid("PT1").status(ThreatIntelSample.Status.NEW)
                .staticDims("{\"java_ghost_client\":\"com.ghost.GhostClient\",\"java_killaura\":\"net.killaura.KillAura\"}")
                .build();

        ThreatIntelSample out = service.analyze(s);

        assertTrue(out.getAutoAnalysis().contains("GHOST_CLIENT"));
        assertTrue(out.getAutoAnalysis().contains("java-ghost-client"));
        assertTrue(out.getAutoAnalysis().contains("\"severity\""));
        assertEquals("java-ghost-client", out.getFamilyLabel());
    }

    @Test
    void clusterSeparatesSimilarFamilies() {
        when(repo.save(any(ThreatIntelSample.class))).thenAnswer(inv -> inv.getArgument(0));
        ThreatIntelSample a = ThreatIntelSample.builder()
                .id("a").pteid("PT1").staticDims("{\"java_ghost_client\":\"com.ghost.GhostClient\"}").build();
        ThreatIntelSample b = ThreatIntelSample.builder()
                .id("b").pteid("PT2").staticDims("{\"kernel_driver\":\"C:\\\\Prog\\\\hack.sys\"}").build();

        var summary = service.cluster(List.of(a, b), 2);

        assertEquals(2, ((Number) summary.get("analyzed")).intValue());
        assertTrue(a.getFamilyLabel() != null && a.getFamilyLabel().startsWith("CLUSTER_"));
        assertTrue(b.getFamilyLabel() != null && b.getFamilyLabel().startsWith("CLUSTER_"));
        assertTrue(!a.getFamilyLabel().equals(b.getFamilyLabel()));
    }
}