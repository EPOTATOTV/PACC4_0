package com.potatotv.pacc.service.detection.v46;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Signature;
import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.SignatureRepository;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * v4.6 主动学习/概念漂移单测：低置信队列、人工复核回流与正式特征库晋升。
 */
class ActiveLearningServiceTest {

    private final ZeroDayFindingRepository zRepo = mock(ZeroDayFindingRepository.class);
    private final ThreatIntelSampleRepository tRepo = mock(ThreatIntelSampleRepository.class);
    private final SignatureRepository sRepo = mock(SignatureRepository.class);
    private final ActiveLearningService service =
            new ActiveLearningService(zRepo, tRepo, sRepo, new ObjectMapper());

    @Test
    void zeroDayQueueListsOpenFindings() {
        ZeroDayFinding f = ZeroDayFinding.builder().id("z1").pteid("PT1").confidenceTier("LOW").build();
        when(zRepo.findByStatusOrderByCreatedAtDesc(ZeroDayFinding.Status.OPEN))
                .thenReturn(List.of(f));
        assertEquals(1, service.zeroDayQueue().size());
        assertEquals("z1", service.zeroDayQueue().get(0).getId());
    }

    @Test
    void reviewFindingFinalizesOpenRecord() {
        ZeroDayFinding f = ZeroDayFinding.builder().id("z1").status(ZeroDayFinding.Status.OPEN).build();
        when(zRepo.findById("z1")).thenReturn(Optional.of(f));
        when(zRepo.save(any(ZeroDayFinding.class))).thenAnswer(inv -> inv.getArgument(0));

        ZeroDayFinding out = service.reviewFinding("z1", true, "admin@tv", "确认样本");

        assertEquals(ZeroDayFinding.Status.REVIEWED, out.getStatus());
        assertEquals(Boolean.TRUE, out.getConfirmed());
        assertEquals("admin@tv", out.getReviewer());
        assertEquals("确认样本", out.getReviewComment());
        assertTrue(out.getReviewedAt() != null);
    }

    @Test
    void reviewThreatFinalizesNewSample() {
        ThreatIntelSample s = ThreatIntelSample.builder().id("t1").status(ThreatIntelSample.Status.NEW).build();
        when(tRepo.findById("t1")).thenReturn(Optional.of(s));
        when(tRepo.save(any(ThreatIntelSample.class))).thenAnswer(inv -> inv.getArgument(0));

        ThreatIntelSample out = service.reviewThreat("t1", false, "op@tv");

        assertEquals(ThreatIntelSample.Status.REVIEWED, out.getStatus());
        assertEquals(Boolean.FALSE, out.getConfirmed());
    }

    @Test
    void pendingCountsReflectOpen() {
        when(zRepo.countByStatus(ZeroDayFinding.Status.OPEN)).thenReturn(3L);
        when(tRepo.countByStatus(ThreatIntelSample.Status.NEW)).thenReturn(2L);
        assertEquals(3, service.pendingZeroDayCount());
        assertEquals(2, service.pendingThreatCount());
    }

    @Test
    void promoteConfirmedSampleCreatesDraftSignature() {
        ThreatIntelSample s = ThreatIntelSample.builder()
                .id("t1").pteid("PT1").edition("JAVA").confirmed(Boolean.TRUE)
                .familyLabel("java-ghost-client").family("FAM_abc12345")
                .autoAnalysis("{\"severity\":90,\"tier\":\"HIGH\"}")
                .generatedRule("{\"family\":\"FAM_abc12345\",\"checks\":[{\"k\":\"java_ghost_client\",\"v\":\"com.x.Ghost\",\"op\":\"eq\"}]}")
                .build();
        when(tRepo.findById("t1")).thenReturn(Optional.of(s));
        when(sRepo.save(any(Signature.class))).thenAnswer(inv -> inv.getArgument(0));

        Signature out = service.promoteThreat("t1", "op@tv");

        assertEquals("java-ghost-client", out.getName());
        assertEquals("java_ghost_client", out.getPattern());
        assertEquals(Signature.Edition.JAVA, out.getEdition());
        assertEquals(5, out.getRiskLevel());
        assertEquals("DRAFT", out.getState());
        assertEquals("op@tv", out.getCreatedBy());
    }

    @Test
    void promoteUnconfirmedSampleThrows() {
        ThreatIntelSample s = ThreatIntelSample.builder()
                .id("t1").pteid("PT1").confirmed(Boolean.FALSE)
                .generatedRule("{\"checks\":[{\"k\":\"k1\",\"v\":\"v1\",\"op\":\"eq\"}]}")
                .build();
        when(tRepo.findById("t1")).thenReturn(Optional.of(s));

        assertThrows(IllegalStateException.class, () -> service.promoteThreat("t1", "op@tv"));
    }
}