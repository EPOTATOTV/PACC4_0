package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.ConfidenceTier;
import com.potatotv.pacc.domain.SuspicionFlag;
import com.potatotv.pacc.repository.SuspicionFlagRepository;
import com.potatotv.pacc.service.HardwareFingerprintService.HardwareVerdict;
import com.potatotv.pacc.service.IntegrityGuardService.IntegrityReport;
import com.potatotv.pacc.service.IntegrityGuardService.State;
import com.potatotv.pacc.util.InputTimingAnalyzer;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * v4.5 对抗风险融合单测：三级置信度、完整性 TAMPERED 强制红屏、MEDIUM 落旗舰类型。
 */
class CounterMeasureRiskServiceTest {

    private HardwareFingerprintService hardwareService;
    private IntegrityGuardService integrityGuard;
    private ConfidenceService confidenceService;
    private RedscreenService redscreenService;
    private SuspicionFlagRepository flagRepo;
    private CounterMeasureRiskService service;

    @BeforeEach
    void setUp() {
        hardwareService = mock(HardwareFingerprintService.class);
        integrityGuard = mock(IntegrityGuardService.class);
        confidenceService = new ConfidenceService(70, 85);
        redscreenService = mock(RedscreenService.class);
        flagRepo = mock(SuspicionFlagRepository.class);
        service = new CounterMeasureRiskService(hardwareService, integrityGuard, confidenceService,
                redscreenService, flagRepo);
    }

    @Test
    void cleanAllIsLowTier() {
        var r = service.fuse(HardwareVerdict.none(70),
                new InputTimingAnalyzer.MacroVerdict(0, 0, 0, 0, false),
                new IntegrityReport(0, State.CLEAN, List.of()));
        assertEquals(ConfidenceTier.LOW, r.tier());
        assertFalse(r.forcedRedscreen());
    }

    @Test
    void lowHardwareAndCleanIntegrityStaysLow() {
        // 硬件 30 (45%) + 0 + 0 → 13.5 → LOW
        var r = service.fuse(new HardwareVerdict(true, "minor", "GENERIC", 30, "match"),
                new InputTimingAnalyzer.MacroVerdict(0, 0, 0, 0, false),
                new IntegrityReport(0, State.CLEAN, List.of()));
        assertEquals(14, r.riskScore());
        assertEquals(ConfidenceTier.LOW, r.tier());
    }

    @Test
    void tamperedIntegrityForcesRedscreen() {
        when(integrityGuard.assess(any())).thenReturn(new IntegrityReport(60, State.TAMPERED, List.of()));
        var r = service.handle("PT42", "JAVA", null, null,
                new IntegrityGuardService.Input(true, false, true, true, List.of("x")));
        assertTrue(r.forcedRedscreen());
        assertEquals(ConfidenceTier.HIGH, r.tier());
        verify(redscreenService).decideAndHandle(eq("PT42"), eq("INTEGRITY_TAMPERED"), anyInt(), eq("JAVA"), anyString());
        verify(flagRepo, never()).save(any(SuspicionFlag.class));
    }

    @Test
    void mediumHardwareWritesHardwareFlag() {
        // 硬件 100(45%) + 宏 100(25%) = 70 → MEDIUM；完整性 CLEAN → 记 HARDWARE_CHEAT
        when(hardwareService.scan(any())).thenReturn(new HardwareVerdict(true, "titan", "TITAN_TWO", 100, "vid_pid_match"));
        when(integrityGuard.assess(any())).thenReturn(new IntegrityReport(0, State.CLEAN, List.of()));

        var r = service.handle("PT9", "JAVA", null, List.of(100.0, 100.0, 100.0, 100.0, 100.0), null);

        assertEquals(ConfidenceTier.MEDIUM, r.tier());
        assertFalse(r.forcedRedscreen());
        verify(redscreenService, never()).decideAndHandle(anyString(), anyString(), anyInt(), anyString(), anyString());

        ArgumentCaptor<SuspicionFlag> cap = ArgumentCaptor.forClass(SuspicionFlag.class);
        verify(flagRepo).save(cap.capture());
        assertEquals(SuspicionFlag.Kind.HARDWARE_CHEAT, cap.getValue().getKind());
        assertEquals("PT9", cap.getValue().getPteid());
    }

    @Test
    void mediumIntegritySuspicionWritesTamperedFlag() {
        // 硬件 88(45%=39.6) + 宏 100(25%=25) + 完整 55(30%=16.5) → 81 → MEDIUM，完整 SUSPECT → TAMPERED_INTEGRITY
        when(hardwareService.scan(any())).thenReturn(new HardwareVerdict(true, "titan", "TITAN_TWO", 88, "match"));
        when(integrityGuard.assess(any())).thenReturn(new IntegrityReport(55, State.SUSPECT, List.of("testsigning_enabled")));

        var r = service.handle("PT7", "JAVA", null, List.of(100.0, 100.0, 100.0, 100.0, 100.0), null);

        assertEquals(ConfidenceTier.MEDIUM, r.tier());
        ArgumentCaptor<SuspicionFlag> cap = ArgumentCaptor.forClass(SuspicionFlag.class);
        verify(flagRepo).save(cap.capture());
        assertEquals(SuspicionFlag.Kind.TAMPERED_INTEGRITY, cap.getValue().getKind());
    }
}