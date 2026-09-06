package com.potatotv.pacc.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CounterMeasureEnvironmentService 纯函数评分单测（默认阈值 suspect=30 / high=60）。
 */
class CounterMeasureEnvironmentServiceTest {

    private final CounterMeasureEnvironmentService svc = new CounterMeasureEnvironmentService(30, 60);

    @Test
    void cleanEnvironmentIsLow() {
        var a = svc.assess(true, true, false, false, false, List.of());
        assertEquals(0, a.score());
        assertEquals(CounterMeasureEnvironmentService.Level.LOW, a.level());
        assertNotNull(a.findings());
    }

    @Test
    void singleDisabledIommuIsLow() {
        // 单条 iommu_disabled = 25 < suspect(30)
        var a = svc.assess(false, true, false, false, false, List.of());
        assertEquals(25, a.score());
        assertEquals(CounterMeasureEnvironmentService.Level.LOW, a.level());
        assertTrue(a.findings().contains("dma:ioommu_disabled"));
    }

    @Test
    void combinedIommuPlusPcieIsMedium() {
        // 25 + 15 = 40 ≥ suspect(30)
        var a = svc.assess(false, true, false, true, false, List.of());
        assertEquals(40, a.score());
        assertEquals(CounterMeasureEnvironmentService.Level.MEDIUM, a.level());
    }

    @Test
    void acpiTamperPlusMemoryReadIsHigh() {
        var a = svc.assess(true, false, false, true, true, List.of());
        // 35 + 15 + 30 = 80
        assertEquals(80, a.score());
        assertEquals(CounterMeasureEnvironmentService.Level.HIGH, a.level());
        assertTrue(a.findings().contains("dma:acpi_tampered"));
        assertTrue(a.findings().contains("dma:pcie_suspicious"));
        assertTrue(a.findings().contains("dma:memory_read_pattern"));
    }

    @Test
    void kernelDebuggerPlusFindingIsMedium() {
        // 内核调试 25 + 单条反调试 10 = 35 ≥ suspect(30)
        var a = svc.assess(true, true, true, false, false, List.of("hardware_breakpoint"));
        assertEquals(35, a.score());
        assertEquals(CounterMeasureEnvironmentService.Level.MEDIUM, a.level());
        assertTrue(a.findings().contains("debug:kernel_debugger"));
    }

    @Test
    void antidebugFindingsAddPerItem() {
        // 3 条反调试 = 30 ≥ suspect(30) → MEDIUM
        var a = svc.assess(true, true, false, false, false, List.of("user_debugger", "seh_hooked", "veh_present"));
        assertEquals(30, a.score());
        assertEquals(CounterMeasureEnvironmentService.Level.MEDIUM, a.level());
        assertTrue(a.findings().contains("debug:user_debugger"));
        assertEquals(3, a.findings().stream().filter(f -> f.startsWith("debug:")).count());
    }

    @Test
    void scoreCappedAt100() {
        var a = svc.assess(false, false, true, true, true, List.of("a", "b", "c", "d", "e"));
        assertTrue(a.score() <= 100);
    }

    @Test
    void nullFindingsTreatedAsClean() {
        var a = svc.assess(true, true, false, false, false, null);
        assertEquals(0, a.score());
        assertEquals(CounterMeasureEnvironmentService.Level.LOW, a.level());
    }

    @Test
    void dryHighLevel() {
        // 断言高分一定落 HIGH
        var a = svc.assess(false, false, true, true, true, List.of("breakpoint_seen"));
        assertTrue(a.score() >= 60);
        assertEquals(CounterMeasureEnvironmentService.Level.HIGH, a.level());
    }

    @Test
    void splitFindingsIgnoresBlanks() {
        var parts = CounterMeasureEnvironmentService.splitFindings(" a , b ,,");
        assertEquals(List.of("a", "b"), parts);
    }

    @Test
    void configViewExposesThresholds() {
        var view = svc.configView();
        assertEquals(30, view.get("environment_suspect"));
        assertEquals(60, view.get("environment_high"));
    }
}