package com.potatotv.pacc.service.detection.v47;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.IocIndicator;
import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.repository.IocIndicatorRepository;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IocServiceTest {

    private IocIndicatorRepository repo;
    private ThreatIntelSampleRepository samples;
    private IocService service;

    @BeforeEach
    void setup() {
        repo = mock(IocIndicatorRepository.class);
        samples = mock(ThreatIntelSampleRepository.class);
        service = new IocService(repo, samples, new ObjectMapper());
    }

    private ThreatIntelSample sample() {
        return ThreatIntelSample.builder()
                .id("s1").pteid("PT0000000001").edition("JAVA")
                .md5("d41d8cd98f00b204e9800998ecf8427e")
                .sha1("da39a3ee5e6b4b0d3255bfef95601890afd80709")
                .family("f1").familyLabel("CLUSTER_0")
                .staticDims("{\"java_ghost_client\":\"com.x.Ghost\",\"loader_path\":\"x/loader.dll\"}")
                .autoAnalysis("{\"tier\":\"HIGH\",\"severity\":92,\"indicators\":[\"com.x.Ghost\",\"evil/loader.dll\"]}")
                .build();
    }

    @Test
    void importExtractsHashesFamilyAndIndicators() {
        ThreatIntelSample s = sample();
        when(samples.findById("s1")).thenReturn(Optional.of(s));
        when(repo.findByValueAndType(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        List<IocIndicator> created = service.importFromSample("s1");
        // md5 + sha1 + CLUSTER_0 家族 + 2 个 indicators（+ 可能重复）→ 至少 5 个
        assertTrue(created.size() >= 5);
        assertTrue(created.stream().anyMatch(i -> i.getType().equals("FILE_HASH")));
        assertTrue(created.stream().anyMatch(i -> i.getType().equals("CLIENT_FAMILY") && i.getValue().equals("CLUSTER_0")));
        // 高严重度样本 → severity 映射到 5
        assertTrue(created.stream().allMatch(i -> i.getSeverity() == 5));
    }

    @Test
    void subscribeReopensDisarmedIoc() {
        IocIndicator i = IocIndicator.builder().id(1L).value("com.x.Ghost").type("STRING")
                .state("DISARMED").subscribed(false).build();
        when(repo.findById(1L)).thenReturn(Optional.of(i));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IocIndicator out = service.subscribe(1L);
        assertTrue(out.isSubscribed());
        assertEquals("OPEN", out.getState());
    }

    @Test
    void disarmClearsSubscription() {
        IocIndicator i = IocIndicator.builder().id(1L).value("com.x.Ghost").type("STRING")
                .state("OPEN").subscribed(true).build();
        when(repo.findById(1L)).thenReturn(Optional.of(i));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        IocIndicator out = service.disarm(1L);
        assertEquals("DISARMED", out.getState());
        assertFalse(out.isSubscribed());
    }

    @Test
    void hitTriggersAlertAtThreshold() {
        IocIndicator i = IocIndicator.builder().id(1L).value("com.x.Ghost").type("STRING")
                .state("OPEN").subscribed(true).hitCount(2).alertThreshold(3).build();
        when(repo.findById(1L)).thenReturn(Optional.of(i));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> r = service.hit(1L);
        assertEquals(3L, ((Number) r.get("hit_count")).longValue());
        assertEquals(true, r.get("alert_triggered"));
    }

    @Test
    void overviewCountsStates() {
        when(repo.findAll()).thenReturn(List.of(
                IocIndicator.builder().type("FILE_HASH").state("OPEN").build(),
                IocIndicator.builder().type("STRING").state("DISARMED").build()));
        when(repo.countByState("OPEN")).thenReturn(1L);
        when(repo.countByState("DISARMED")).thenReturn(1L);
        when(repo.countBySubscribedTrue()).thenReturn(0L);
        when(repo.countBySeverityGreaterThanEqual(4)).thenReturn(1L);
        when(repo.count()).thenReturn(2L);

        Map<String, Object> o = service.overview();
        assertEquals(1L, o.get("open"));
        assertEquals(2L, o.get("total"));
        Map<?, ?> byType = (Map<?, ?>) o.get("by_type");
        assertEquals(1L, byType.get("FILE_HASH"));
    }

    @Test
    void nonEncryptedSampleImportSimplyNoCrash() {
        ThreatIntelSample s = ThreatIntelSample.builder().id("s2").edition("JAVA")
                .staticDims("{}").build();
        when(samples.findById("s2")).thenReturn(Optional.of(s));
        when(repo.findByValueAndType(any(), any())).thenReturn(Optional.empty());
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        List<IocIndicator> created = service.importFromSample("s2");
        // 无可抽取指标 → 空集合，不抛异常
        assertEquals(0, created.size());
    }
}