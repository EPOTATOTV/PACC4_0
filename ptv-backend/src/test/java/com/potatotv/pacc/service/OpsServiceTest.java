package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.ClientCrashReport;
import com.potatotv.pacc.domain.ClientTelemetry;
import com.potatotv.pacc.domain.RemoteConfig;
import com.potatotv.pacc.repository.ClientCrashReportRepository;
import com.potatotv.pacc.repository.ClientTelemetryRepository;
import com.potatotv.pacc.repository.RemoteConfigRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v4.7 自动化运维单测：崩溃上报落库、性能聚合、远程配置 upsert 后 active 返回。
 */
class OpsServiceTest {

    private ClientCrashReportRepository crashRepo;
    private ClientTelemetryRepository telemetryRepo;
    private RemoteConfigRepository configRepo;
    private OpsService service;

    @BeforeEach
    void setUp() {
        crashRepo = mock(ClientCrashReportRepository.class);
        telemetryRepo = mock(ClientTelemetryRepository.class);
        configRepo = mock(RemoteConfigRepository.class);
        service = new OpsService(crashRepo, telemetryRepo, configRepo);
    }

    @Test
    void reportCrashPersistsFields() {
        when(crashRepo.save(any(ClientCrashReport.class))).thenAnswer(inv -> inv.getArgument(0));
        ClientCrashReport saved = service.reportCrash(
                "pte-1", "1.4.0", "Windows 11", "x86_64",
                "android", "java.lang.NullPointerException", "{\"scene\":\"login\"}", "AntiCheat");
        assertNotNull(saved.getId());
        assertEquals(ClientCrashReport.Platform.ANDROID, saved.getPlatform());
        assertEquals("pte-1", saved.getPteid());
        assertEquals("java.lang.NullPointerException", saved.getStackTrace());
        verify(crashRepo).save(any(ClientCrashReport.class));
    }

    @Test
    void reportCrashToleratesBlankPteidAndUnknownPlatform() {
        when(crashRepo.save(any(ClientCrashReport.class))).thenAnswer(inv -> inv.getArgument(0));
        ClientCrashReport saved = service.reportCrash(
                "  ", "1.4.0", "Win", "amd64", "SOLARIS", null, null, null);
        assertNull(saved.getPteid());
        assertEquals(ClientCrashReport.Platform.WINDOWS, saved.getPlatform());
    }

    @Test
    void telemetryAverageComputesMean() {
        ClientTelemetry a = ClientTelemetry.builder().cpuPercent(10).memMb(100).build();
        ClientTelemetry b = ClientTelemetry.builder().cpuPercent(30).memMb(300).build();
        ClientTelemetry c = ClientTelemetry.builder().cpuPercent(50).memMb(500).build();
        OpsService.TelemetryAverage avg = OpsService.average(List.of(a, b, c));
        assertEquals(30.0, avg.avgCpu(), 1e-9);
        assertEquals(300.0, avg.avgMemMb(), 1e-9);
        assertEquals(3, avg.count());
    }

    @Test
    void telemetryAverageEmptyReturnsZero() {
        OpsService.TelemetryAverage avg = OpsService.average(List.of());
        assertEquals(0, avg.count());
        assertEquals(0.0, avg.avgCpu(), 1e-9);
        assertEquals(0.0, avg.avgMemMb(), 1e-9);
    }

    @Test
    void configUpsertThenActiveReturnsEffectiveValue() {
        RemoteConfig saved = RemoteConfig.builder()
                .id("scan.interval")
                .category(RemoteConfig.Category.SCAN)
                .intValue(1500)
                .updatedBy("admin")
                .build();
        when(configRepo.findById("scan.interval")).thenReturn(Optional.empty());
        when(configRepo.save(any(RemoteConfig.class))).thenReturn(saved);
        RemoteConfig cfg = service.upsertConfig("scan.interval", "SCAN", 1500, null, null, "admin");
        assertEquals("scan.interval", cfg.getId());
        assertEquals(RemoteConfig.Category.SCAN, cfg.getCategory());
        assertEquals(1500, cfg.getIntValue());
        verify(configRepo).save(any(RemoteConfig.class));

        RemoteConfig withOnlyInt = RemoteConfig.builder().id("scan.interval").category(RemoteConfig.Category.SCAN).intValue(1500).build();
        RemoteConfig withDouble = RemoteConfig.builder().id("throttle.ratio").category(RemoteConfig.Category.THROTTLE).doubleValue(0.35).build();
        RemoteConfig invalid = RemoteConfig.builder().id("bad").category(RemoteConfig.Category.DETECTION).intValue(1).doubleValue(2.0).build();
        when(configRepo.findAll()).thenReturn(List.of(withOnlyInt, withDouble, invalid));
        Map<String, Object> active = service.activeConfig();
        assertTrue(active.containsKey("scan.interval"));
        assertEquals(1500, active.get("scan.interval"));
        assertEquals(0.35, (Double) active.get("throttle.ratio"), 1e-9);
        assertFalse(active.containsKey("bad"));
    }

    @Test
    void configUpsertUpdatesExisting() {
        RemoteConfig existing = RemoteConfig.builder()
                .id("detect.enabled").category(RemoteConfig.Category.DETECTION).boolValue(false).build();
        when(configRepo.findById("detect.enabled")).thenReturn(Optional.of(existing));
        when(configRepo.save(any(RemoteConfig.class))).thenAnswer(inv -> inv.getArgument(0));
        RemoteConfig cfg = service.upsertConfig("detect.enabled", "DETECTION", null, null, true, "op");
        assertEquals(Boolean.TRUE, cfg.getBoolValue());
        assertEquals("op", cfg.getUpdatedBy());
        verify(configRepo).save(existing);
    }
}
