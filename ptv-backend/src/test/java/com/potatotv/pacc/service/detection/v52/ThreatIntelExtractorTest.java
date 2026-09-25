package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.IocIndicator;
import com.potatotv.pacc.repository.IocIndicatorRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §6.3 IOC 自动提取测试：从红屏明细里按「值形态 + 字段名」双路识别指标、按 (值,类型) 去重、
 * 命中既有指标时累加命中数、归族落到已知客户端名或事件类型。
 */
class ThreatIntelExtractorTest {

    private IocIndicatorRepository iocs;
    private ThreatIntelExtractor extractor;

    @BeforeEach
    void setUp() {
        iocs = mock(IocIndicatorRepository.class);
        when(iocs.findByValueAndType(anyString(), anyString())).thenReturn(Optional.empty());
        when(iocs.findFirstByTypeAndValueStartingWith(anyString(), anyString())).thenReturn(Optional.empty());
        when(iocs.save(any(IocIndicator.class))).thenAnswer(inv -> inv.getArgument(0));
        extractor = new ThreatIntelExtractor(iocs, new ObjectMapper());
    }

    @Test
    void extractsMixedIndicatorTypesFromDetailJson() {
        String detail = """
                {"pcie_dma_present":true,
                 "suspicious_pcie":"10EE:9038(Xilinx FPGA)",
                 "unknown_agents":"-javaagent:/opt/wurst/inject.jar",
                 "attach_artifacts":"/tmp/.attach_pid1234",
                 "unknown_thread_names":"ImpactWorker",
                 "driver":"C:\\\\Windows\\\\System32\\\\drivers\\\\pcileech.sys",
                 "hash":"9f2c1d4e5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f",
                 "c2":"45.77.12.9",
                 "url_domain":"cheat-panel.example.com",
                 "usb":"VID_1A86&PID_7523"}""";

        List<ThreatIntelExtractor.Candidate> candidates = extractor.extract(detail);

        assertTrue(candidates.size() >= 8, "应提取出多类指标，实际 " + candidates.size());
        assertHas(candidates, "HW_DEVICE", "PCI:10EE:9038");
        assertHas(candidates, "FILE_PATH", "-javaagent:/opt/wurst/inject.jar");
        assertHas(candidates, "FILE_PATH", "/tmp/.attach_pid1234");
        assertHas(candidates, "DRIVER", "C:\\Windows\\System32\\drivers\\pcileech.sys");
        assertHas(candidates, "IP", "45.77.12.9");
        assertHas(candidates, "DOMAIN", "cheat-panel.example.com");
        assertHas(candidates, "USB_DEVICE", "USB:1A86:7523");
        assertHas(candidates, "CLIENT_FAMILY", "Wurst");
        assertHas(candidates, "CLIENT_FAMILY", "Impact");
        assertHas(candidates, "CLIENT_FAMILY", "PCILeech");
        assertTrue(candidates.stream().anyMatch(c -> c.type().equals("FILE_HASH")
                && c.value().equals("9f2c1d4e5a6b7c8d9e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d5e6f")),
                "SHA-256 应作为文件哈希提取");
    }

    @Test
    void storesNewIndicatorsWithEventTypeAsFamily() {
        ThreatIntelExtractor.Extraction extraction = extractor.extractAndStore(
                "reflective_dll", "{\"unknown_agents\":\"/tmp/evil-hook.jar\"}", "alert_1");

        assertFalse(extraction.created().isEmpty());
        assertEquals(0, extraction.matched().size());
        IocIndicator ioc = extraction.created().get(0);
        assertEquals("alert_1", ioc.getSourceId());
        assertEquals(1, ioc.getHitCount());
        assertEquals("reflective_dll", ioc.getSourceFamily(), "无客户端关键词时以事件类型归族");
        assertEquals(IocIndicator.State.OPEN.name(), ioc.getState());
    }

    @Test
    void existingIndicatorIsMatchedAndHitCountIncremented() {
        IocIndicator existing = IocIndicator.builder()
                .value("/tmp/evil-hook.jar").type("FILE_PATH").sourceFamily("Wurst")
                .hitCount(4).build();
        when(iocs.findByValueAndType("/tmp/evil-hook.jar", "FILE_PATH")).thenReturn(Optional.of(existing));

        ThreatIntelExtractor.Extraction extraction = extractor.extractAndStore(
                "reflective_dll", "{\"agents\":\"/tmp/evil-hook.jar\"}", "alert_2");

        assertEquals(0, extraction.created().size(), "已存在的指标不得重复入库");
        assertEquals(1, extraction.matched().size());
        assertEquals(5, existing.getHitCount());
        assertEquals("alert_2", existing.getSourceId());
    }

    @Test
    void nonJsonDetailFallsBackToTextScanning() {
        List<ThreatIntelExtractor.Candidate> candidates =
                extractor.extract("进程 pcileech.exe 连接 45.77.12.9 已拦截");

        assertHas(candidates, "FILE_PATH", "pcileech.exe");
        assertHas(candidates, "IP", "45.77.12.9");
        assertHas(candidates, "CLIENT_FAMILY", "PCILeech");
    }

    @Test
    void blankDetailYieldsNothingAndNeverThrows() {
        assertTrue(extractor.extract(null).isEmpty());
        assertTrue(extractor.extract("   ").isEmpty());
        assertTrue(extractor.extract("{}").isEmpty());
    }

    @Test
    void candidateCountIsCappedPerEvent() {
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < 60; i++) {
            sb.append("\"k").append(i).append("\":\"/opt/cheat/plugin").append(i).append(".dll\",");
        }
        sb.append("\"last\":\"1\"}");

        List<ThreatIntelExtractor.Candidate> candidates = extractor.extract(sb.toString());

        assertEquals(ThreatIntelExtractor.MAX_PER_EVENT, candidates.size(), "单事件候选数必须有上限");
    }

    private static void assertHas(List<ThreatIntelExtractor.Candidate> candidates, String type, String value) {
        assertTrue(candidates.stream().anyMatch(c -> c.type().equals(type) && c.value().equals(value)),
                "缺少指标 " + type + "=" + value + "，实际：" + candidates);
    }
}