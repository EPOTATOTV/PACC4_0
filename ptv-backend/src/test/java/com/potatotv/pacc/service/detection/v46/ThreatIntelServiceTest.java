package com.potatotv.pacc.service.detection.v46;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.ThreatIntelSample;
import com.potatotv.pacc.repository.ThreatIntelSampleRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * v4.6 威胁情报单测：哈希归族确定性、规则生成、特征库匹配。
 */
class ThreatIntelServiceTest {

    private final ThreatIntelSampleRepository repo = mock(ThreatIntelSampleRepository.class);

    @Test
    void familyDerivedFromHashPrefix() {
        assertEquals("FAM_0f6d7a1c", ThreatIntelService.deriveFamily("0F6D7A1C23456789abcdef"));
        assertEquals("FAM_unknown".toUpperCase(), ThreatIntelService.deriveFamily(null));
        assertEquals("FAM_unknown".toUpperCase(), ThreatIntelService.deriveFamily(""));
    }

    @Test
    void samePrefixClustersTogether() {
        String a = ThreatIntelService.deriveFamily("abcd1234...");
        String b = ThreatIntelService.deriveFamily("abcd1234xyz");
        String c = ThreatIntelService.deriveFamily("99990000");
        assertEquals(a, b);
        assertTrue(!a.equals(c));
    }

    @Test
    void ingestGeneratesFamilyAndRule() {
        when(repo.save(any(ThreatIntelSample.class))).thenAnswer(inv -> inv.getArgument(0));
        ThreatIntelService service = new ThreatIntelService(repo);
        ThreatIntelSample s = service.ingest("PT1", "JAVA", "md5abc", "sha1def",
                Map.of("java_ghost_client", "com.x.Ghost", "java_killaura", "net.y.Kill"));
        assertNotNull(s.getId());
        assertEquals("FAM_sha1def", s.getFamily());
        assertTrue(s.getGeneratedRule().contains("java_ghost_client"));
        assertTrue(s.getGeneratedRule().startsWith("{"));
        assertEquals(ThreatIntelSample.Status.NEW, s.getStatus());
    }

    @Test
    void signatureExpansionMatchesSeedDims() {
        List<SignatureExpansionService.Matched> m =
                new SignatureExpansionService().match(SignatureExpansionService.demoStaticDims());
        assertTrue(m.size() > 0);
        // 演示样本含高危 ghost_client → 命中的种子风险应 ≥4
        assertTrue(m.get(0).riskLevel() >= 4);
    }
}