package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.ReleaseInfo;
import com.potatotv.pacc.domain.UpdateReport;
import com.potatotv.pacc.repository.ReleaseRepository;
import com.potatotv.pacc.repository.UpdateReportRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * PCU 更新检查 / 上报单测：无更新、有更新、delta 命中与不命中、强制更新、非法入参，
 * 以及上报状态归一化与错误信息截断。
 */
class PcuUpdateServiceTest {

    private ReleaseRepository releaseRepository;
    private UpdateReportRepository updateReportRepository;
    private PcuUpdateService service;

    @BeforeEach
    void setUp() {
        releaseRepository = mock(ReleaseRepository.class);
        updateReportRepository = mock(UpdateReportRepository.class);
        service = new PcuUpdateService(new ReleaseService(releaseRepository), updateReportRepository);
    }

    private static ReleaseInfo release(String version, String fromVersion, String deltaUrl) {
        return ReleaseInfo.builder()
                .id("r-" + version)
                .platform("windows")
                .channel("stable")
                .version(version)
                .status("PUBLISHED")
                .downloadUrl("https://dl.example.com/pacc-" + version + ".zip")
                .sha256("abc123")
                .fileSize(25165824L)
                .notes("修复了若干问题")
                .minAppVersion("5.4.0")
                .signature("c2lnbmF0dXJl")
                .deltaFromVersion(fromVersion)
                .deltaUrl(deltaUrl)
                .deltaSha256("def456")
                .deltaSize(2097152L)
                .build();
    }

    private void stubPublished(String platform, String channel, ReleaseInfo... rows) {
        when(releaseRepository.findByPlatformAndChannelAndStatus(platform, channel, "PUBLISHED"))
                .thenReturn(List.of(rows));
    }

    @Test
    void noPublishedReleaseYieldsNoUpdate() {
        stubPublished("windows", "stable");
        Map<String, Object> res = service.check("windows", "5.4.0", "stable", "pte-1");
        assertEquals(Boolean.FALSE, res.get("has_update"));
        assertEquals("5.4.0", res.get("latest_version"));
        assertEquals("windows", res.get("platform"));
        assertFalse(res.containsKey("download_url"));
    }

    @Test
    void latestNotHigherYieldsNoUpdate() {
        stubPublished("windows", "stable", release("5.4.0", null, null));
        Map<String, Object> res = service.check("windows", "5.4.0", "stable", null);
        assertEquals(Boolean.FALSE, res.get("has_update"));
        assertEquals("5.4.0", res.get("latest_version"));
    }

    @Test
    void higherReleaseYieldsFullManifestWithoutDelta() {
        stubPublished("windows", "stable", release("5.4.1", "5.3.0", "https://dl.example.com/d.zip"));
        Map<String, Object> res = service.check("windows", "5.4.0", "stable", null);
        assertEquals(Boolean.TRUE, res.get("has_update"));
        assertEquals("windows", res.get("platform"));
        assertEquals("5.4.1", res.get("latest_version"));
        assertEquals("https://dl.example.com/pacc-5.4.1.zip", res.get("download_url"));
        assertEquals("sha256:abc123", res.get("checksum"));
        assertEquals(25165824L, res.get("size"));
        assertEquals(Boolean.FALSE, res.get("force_update"));
        assertEquals("修复了若干问题", res.get("changelog"));
        assertEquals("5.4.0", res.get("min_app_version"));
        assertEquals("c2lnbmF0dXJl", res.get("signature"));
        assertFalse(res.containsKey("delta"));
        // 内部字段绝不外泄
        assertFalse(res.containsKey("id"));
        assertFalse(res.containsKey("status"));
        assertFalse(res.containsKey("created_by"));
        assertFalse(res.containsKey("crash_rate_pct"));
    }

    @Test
    void forcedUpdateAndAlreadyPrefixedChecksumPassThrough() {
        ReleaseInfo r = ReleaseInfo.builder()
                .id("r1").platform("windows").channel("stable").version("6.0.0").status("PUBLISHED")
                .downloadUrl("https://dl.example.com/6.0.0.zip").sha256("sha256:deadbeef")
                .fileSize(1L).forcedEnabled(true).build();
        stubPublished("windows", "stable", r);
        Map<String, Object> res = service.check("windows", "5.4.0", "stable", null);
        assertEquals(Boolean.TRUE, res.get("has_update"));
        assertEquals(Boolean.TRUE, res.get("force_update"));
        assertEquals("sha256:deadbeef", res.get("checksum"));
    }

    @Test
    void deltaIncludedWhenFromVersionMatchesExactly() {
        stubPublished("windows", "stable", release("5.4.1", "5.4.0", "https://dl.example.com/d.zip"));
        Map<String, Object> res = service.check("windows", "5.4.0", "stable", null);
        @SuppressWarnings("unchecked")
        Map<String, Object> delta = (Map<String, Object>) res.get("delta");
        assertEquals("5.4.0", delta.get("from_version"));
        assertEquals("https://dl.example.com/d.zip", delta.get("url"));
        assertEquals("sha256:def456", delta.get("checksum"));
        assertEquals(2097152L, delta.get("size"));
    }

    @Test
    void deltaOmittedWhenFromVersionDiffers() {
        stubPublished("windows", "stable", release("5.4.1", "5.3.0", "https://dl.example.com/d.zip"));
        Map<String, Object> res = service.check("windows", "5.4.0", "stable", null);
        assertFalse(res.containsKey("delta"));
    }

    @Test
    void deltaOmittedWhenUrlBlank() {
        stubPublished("windows", "stable", release("5.4.1", "5.4.0", "  "));
        Map<String, Object> res = service.check("windows", "5.4.0", "stable", null);
        assertFalse(res.containsKey("delta"));
    }

    @Test
    void highestSemanticVersionWinsNotStringOrder() {
        stubPublished("windows", "stable",
                release("v5.9.9", null, null),
                release("5.10.0", null, null),
                release("v5.4.1-rc1", null, null));
        Map<String, Object> res = service.check("windows", "5.4.0", "stable", null);
        assertEquals("5.10.0", res.get("latest_version"));
    }

    @Test
    void invalidInputsFallBackToWindowsStable() {
        stubPublished("windows", "stable", release("1.0.0", null, null));
        Map<String, Object> res = service.check("solaris", null, "weird", null);
        assertEquals(Boolean.TRUE, res.get("has_update"));
        assertEquals("windows", res.get("platform"));
        assertEquals("1.0.0", res.get("latest_version"));
        verify(releaseRepository).findByPlatformAndChannelAndStatus(eq("windows"), eq("stable"), eq("PUBLISHED"));
    }

    @Test
    void blankCurrentVersionTreatedAsZeroZeroZero() {
        stubPublished("windows", "stable");
        Map<String, Object> res = service.check("windows", "   ", "stable", null);
        assertEquals(Boolean.FALSE, res.get("has_update"));
        assertEquals("0.0.0", res.get("latest_version"));
    }

    @Test
    void reportNormalizesStatusAndTruncatesError() {
        when(updateReportRepository.save(any(UpdateReport.class))).thenAnswer(inv -> inv.getArgument(0));
        String longError = "E".repeat(700);
        service.report("pte-1", "android", "5.4.0", "5.4.1", "EXPLODED", longError);

        ArgumentCaptor<UpdateReport> captor = ArgumentCaptor.forClass(UpdateReport.class);
        verify(updateReportRepository).save(captor.capture());
        UpdateReport saved = captor.getValue();
        assertEquals("failed", saved.getStatus());
        assertEquals(500, saved.getErrorMessage().length());
        assertEquals("pte-1", saved.getPteid());
        assertEquals("android", saved.getPlatform());
        assertEquals("5.4.0", saved.getFromVersion());
        assertEquals("5.4.1", saved.getToVersion());
    }

    @Test
    void reportTruncatesFieldsToColumnWidths() {
        when(updateReportRepository.save(any(UpdateReport.class))).thenAnswer(inv -> inv.getArgument(0));
        service.report("p".repeat(100), "a".repeat(40), "1".repeat(60), "2".repeat(60), "success", null);

        ArgumentCaptor<UpdateReport> captor = ArgumentCaptor.forClass(UpdateReport.class);
        verify(updateReportRepository).save(captor.capture());
        UpdateReport saved = captor.getValue();
        assertEquals(64, saved.getPteid().length());
        assertEquals(16, saved.getPlatform().length());
        assertEquals(48, saved.getFromVersion().length());
        assertEquals(48, saved.getToVersion().length());
    }

    @Test
    void reportAcceptsKnownStatusCaseInsensitivelyAndToleratesMissingFields() {
        when(updateReportRepository.save(any(UpdateReport.class))).thenAnswer(inv -> inv.getArgument(0));
        service.report(null, null, null, null, "SUCCESS", "");
        ArgumentCaptor<UpdateReport> captor = ArgumentCaptor.forClass(UpdateReport.class);
        verify(updateReportRepository).save(captor.capture());
        UpdateReport saved = captor.getValue();
        assertEquals("success", saved.getStatus());
        assertNull(saved.getPteid());
        assertNull(saved.getPlatform());
        assertNull(saved.getErrorMessage());
        assertTrue(saved.getId() != null && !saved.getId().isBlank());
    }

    @Test
    void reportBlankStatusFallsBackToFailed() {
        when(updateReportRepository.save(any(UpdateReport.class))).thenAnswer(inv -> inv.getArgument(0));
        service.report("pte-2", "ios", "5.4.0", "5.4.1", "  ", null);
        ArgumentCaptor<UpdateReport> captor = ArgumentCaptor.forClass(UpdateReport.class);
        verify(updateReportRepository).save(captor.capture());
        assertEquals("failed", captor.getValue().getStatus());
    }
}