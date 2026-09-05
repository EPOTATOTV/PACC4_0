package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.CheatHardwareProfile;
import com.potatotv.pacc.domain.CheatHardwareProfile.CheatFlag;
import com.potatotv.pacc.domain.CheatHardwareProfile.DeviceClass;
import com.potatotv.pacc.repository.CheatHardwareProfileRepository;
import com.potatotv.pacc.service.HardwareFingerprintService.HardwareReport;
import com.potatotv.pacc.service.HardwareFingerprintService.HardwareVerdict;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * v4.5 硬件指纹匹配单测：VID/PID 精确、设备类（未知 DMA 卡）、设备字符串指纹、未命中降级。
 */
class HardwareFingerprintServiceTest {

    private CheatHardwareProfileRepository repo;
    private HardwareFingerprintService service;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(CheatHardwareProfileRepository.class);
        service = new HardwareFingerprintService(repo);
    }

    private CheatHardwareProfile profile(String id, String vid, String pid, DeviceClass cls,
                                         String patterns, CheatFlag flag, int risk) {
        return CheatHardwareProfile.builder()
                .id(id).vid(vid).pid(pid).deviceClass(cls)
                .fingerprintPatterns(patterns).cheatFlag(flag).riskScore(risk).active(true)
                .build();
    }

    @Test
    void vidPidExactMatch() {
        when(repo.findByActiveTrueOrderByRiskScoreDesc()).thenReturn(
                List.of(profile("titan", "20d0", "1606", DeviceClass.USB_HID, null, CheatFlag.TITAN_TWO, 88)));
        HardwareVerdict v = service.scan(List.of(
                new HardwareReport("20D0", "1606", DeviceClass.USB_HID, "Titan Two")));
        assertTrue(v.matched());
        assertEquals("titan", v.matchedId());
        assertEquals(88, v.score());
        assertEquals(CheatFlag.TITAN_TWO.name(), v.cheatFlag());
    }

    @Test
    void unknownPcieCardMatchesByDeviceClass() {
        // VLAN DMA 卡：未知 VID/PID，但设备类 PCIE_DATA_ACQ 命中
        when(repo.findByActiveTrueOrderByRiskScoreDesc()).thenReturn(
                List.of(profile("dma", null, null, DeviceClass.PCIE_DATA_ACQ, null, CheatFlag.DMA_CARD, 95)));
        HardwareVerdict v = service.scan(List.of(
                new HardwareReport("", "", DeviceClass.PCIE_DATA_ACQ, "PCIe capture device")));
        assertTrue(v.matched());
        assertEquals("device_class_match:PCIE_DATA_ACQ", v.reason());
        assertEquals(95, v.score());
    }

    @Test
    void deviceStringFingerprintMatchIgnoringCase() {
        when(repo.findByActiveTrueOrderByRiskScoreDesc()).thenReturn(
                List.of(profile("reasnow", null, null, DeviceClass.USB_HID, "reasnow s1,RS", CheatFlag.REASNOW_S1, 75)));
        HardwareVerdict v = service.scan(List.of(
                new HardwareReport("1a86", "55e0", DeviceClass.USB_HID, "Reasnow S1 Kb/Gamepad")));
        assertTrue(v.matched());
        assertEquals("fingerprint_match:reasnow s1", v.reason());
        assertEquals(75, v.score());
    }

    @Test
    void noMatchIsNotMatched() {
        when(repo.findByActiveTrueOrderByRiskScoreDesc()).thenReturn(List.of(
                profile("titan", "20d0", "1606", DeviceClass.USB_HID, null, CheatFlag.TITAN_TWO, 88)));
        HardwareVerdict v = service.scan(List.of(
                new HardwareReport("046d", "c539", DeviceClass.USB_HID, "Logitech G502")));
        assertFalse(v.matched());
    }

    @Test
    void highestRiskSelectedWhenMultipleReports() {
        when(repo.findByActiveTrueOrderByRiskScoreDesc()).thenReturn(List.of(
                profile("titan", "20d0", "1606", DeviceClass.USB_HID, null, CheatFlag.TITAN_TWO, 88),
                profile("reasnow", "1a86", "55e0", DeviceClass.USB_HID, null, CheatFlag.REASNOW_S1, 75)));
        HardwareVerdict v = service.scan(List.of(
                new HardwareReport("20d0", "1606", DeviceClass.USB_HID, "Titan Two"),
                new HardwareReport("1a86", "55e0", DeviceClass.USB_HID, "ReaSnow S1")));
        assertTrue(v.matched());
        assertEquals("titan", v.matchedId());
    }
}