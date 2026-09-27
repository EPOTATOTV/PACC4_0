package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.ReleaseInfo;
import com.potatotv.pacc.repository.UpdateReportRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * PCU 灰度发布测试（设计文档 §5.3，验收 V10：1%→10%→50%→100% 灰度可用）。
 *
 * <p>灰度最容易写错的地方不是分桶，而是「不够资格时怎么办」：如果直接返回 has_update=false，
 * 那些设备会停在上一个版本上，等于老版本也被一起冻住。所以这里的用例重点盯两件事 ——
 * 未放量设备拿到的是上一个已全量版本，以及失败率超线后该版本立刻停止下发。</p>
 */
class PcuUpdateServiceGrayTest {

    private static final String PLATFORM = "windows";
    private static final String CHANNEL = "stable";

    private ReleaseService releaseService;
    private UpdateReportRepository reportRepository;

    @BeforeEach
    void setUp() {
        releaseService = mock(ReleaseService.class);
        reportRepository = mock(UpdateReportRepository.class);
        when(reportRepository.countByToVersionAndCreatedAtAfter(anyString(), any(Instant.class))).thenReturn(0L);
        when(reportRepository.countByToVersionAndStatusInAndCreatedAtAfter(
                anyString(), anyCollection(), any(Instant.class))).thenReturn(0L);
    }

    private PcuUpdateService service(String internalPteids, int limitPct, int minSamples) {
        return new PcuUpdateService(releaseService, reportRepository, internalPteids, limitPct, minSamples, 24);
    }

    private static ReleaseInfo release(String version, int rolloutPercent) {
        return ReleaseInfo.builder()
                .id("rel-" + version)
                .platform(PLATFORM)
                .channel(CHANNEL)
                .version(version)
                .rolloutPercent(rolloutPercent)
                .downloadUrl("https://example.invalid/pacc-" + version + ".zip")
                .sha256("abc")
                .fileSize(1024)
                .status("PUBLISHED")
                .build();
    }

    private void givenReleases(ReleaseInfo... releases) {
        when(releaseService.publishedNewestFirst(PLATFORM, CHANNEL))
                .thenReturn(new ArrayList<>(List.of(releases)));
    }

    /** 找一个当前桶位不低于 pct 的 PTEID，用来构造「未放量」的设备。 */
    private static String pteidOutside(int pct) {
        for (int i = 0; i < 5000; i++) {
            String candidate = "PTE" + i;
            if (PcuUpdateService.bucketOf(candidate) >= pct) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到桶位 >= " + pct + " 的测试 PTEID");
    }

    /** 找一个当前桶位低于 pct 的 PTEID，用来构造「已放量」的设备。 */
    private static String pteidInside(int pct) {
        for (int i = 0; i < 5000; i++) {
            String candidate = "PTE" + i;
            if (PcuUpdateService.bucketOf(candidate) < pct) {
                return candidate;
            }
        }
        throw new IllegalStateException("找不到桶位 < " + pct + " 的测试 PTEID");
    }

    @Test
    void 分桶稳定且落在有效区间() {
        Set<Integer> buckets = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            int bucket = PcuUpdateService.bucketOf("PTE" + i);
            assertTrue(bucket >= 0 && bucket < 100, "桶位必须在 [0,100)：" + bucket);
            buckets.add(bucket);
        }
        // 1000 个样本至少覆盖到 90 个桶，否则说明哈希分布退化（1% 灰度会成片漏人）
        assertTrue(buckets.size() >= 90, "桶分布过于集中，仅覆盖 " + buckets.size() + " 个桶");
        // 同一 PTEID 反复取桶必须一致，否则设备会在灰度中间被踢出/拉入
        assertEquals(PcuUpdateService.bucketOf("PTE123456"), PcuUpdateService.bucketOf("PTE123456"));
        assertNotEquals(PcuUpdateService.bucketOf("PTE1"), PcuUpdateService.bucketOf("PTE2"));
    }

    @Test
    void 全量版本对所有设备下发() {
        givenReleases(release("5.4.1", 100));
        Map<String, Object> out = service("", 5, 20).check(PLATFORM, "5.4.0", CHANNEL, "PTE1");
        assertEquals(true, out.get("has_update"));
        assertEquals("5.4.1", out.get("latest_version"));
        // 没有 PTEID 的设备在全量版本上照常升级
        assertEquals(true, service("", 5, 20).check(PLATFORM, "5.4.0", CHANNEL, null).get("has_update"));
    }

    @Test
    void 灰度中未放量的设备停在旧版本() {
        givenReleases(release("5.4.2", 1), release("5.4.1", 100));
        PcuUpdateService svc = service("", 5, 20);
        String outside = pteidOutside(1);

        Map<String, Object> out = svc.check(PLATFORM, "5.4.0", CHANNEL, outside);
        // 关键：不是 has_update=false，而是继续拿到上一个已全量版本
        assertEquals(true, out.get("has_update"), "未放量设备应当能升到上一个全量版本");
        assertEquals("5.4.1", out.get("latest_version"));
    }

    @Test
    void 灰度中已放量的设备拿到新版本() {
        givenReleases(release("5.4.2", 10), release("5.4.1", 100));
        Map<String, Object> out = service("", 5, 20)
                .check(PLATFORM, "5.4.0", CHANNEL, pteidInside(10));
        assertEquals("5.4.2", out.get("latest_version"));
    }

    @Test
    void 只有灰度版本且设备未放量时无更新() {
        givenReleases(release("5.4.2", 1));
        Map<String, Object> out = service("", 5, 20)
                .check(PLATFORM, "5.4.0", CHANNEL, pteidOutside(1));
        assertEquals(false, out.get("has_update"));
        assertEquals("5.4.0", out.get("latest_version"));
    }

    @Test
    void 比例为零的版本不下发() {
        givenReleases(release("5.4.2", 0), release("5.4.1", 100));
        Map<String, Object> out = service("", 5, 20)
                .check(PLATFORM, "5.4.0", CHANNEL, pteidInside(100));
        assertEquals("5.4.1", out.get("latest_version"));
    }

    @Test
    void 内部测试设备优先放行() {
        givenReleases(release("5.4.2", 1));
        PcuUpdateService svc = service("PTE-INTERNAL-1, PTE-INTERNAL-2", 5, 20);
        // 取一个明确不会落在 1% 桶内的 ID，确保放行来自白名单而不是分桶
        String outside = pteidOutside(1);
        assertEquals(false, svc.check(PLATFORM, "5.4.0", CHANNEL, outside).get("has_update"));
        for (String internal : List.of("PTE-INTERNAL-1", "PTE-INTERNAL-2", " PTE-INTERNAL-2 ")) {
            Map<String, Object> out = svc.check(PLATFORM, "5.4.0", CHANNEL, internal);
            assertEquals(true, out.get("has_update"), internal + " 应当优先放行");
            assertEquals("5.4.2", out.get("latest_version"));
        }
    }

    @Test
    void 失败率超线时该版本停止下发() {
        givenReleases(release("5.4.2", 50));
        // 50 个样本里 5 个失败 = 10% > 5%
        when(reportRepository.countByToVersionAndCreatedAtAfter(anyString(), any(Instant.class)))
                .thenReturn(50L);
        when(reportRepository.countByToVersionAndStatusInAndCreatedAtAfter(
                anyString(), anyCollection(), any(Instant.class))).thenReturn(5L);
        Map<String, Object> out = service("", 5, 20)
                .check(PLATFORM, "5.4.0", CHANNEL, pteidInside(50));
        assertEquals(false, out.get("has_update"), "失败率超线的版本必须停止下发");
    }

    @Test
    void 样本不足时不做失败率判定() {
        givenReleases(release("5.4.2", 50));
        // 2 个样本 1 个失败 = 50%，但样本数低于 minSamples=20，不该据此暂停
        when(reportRepository.countByToVersionAndCreatedAtAfter(anyString(), any(Instant.class)))
                .thenReturn(2L);
        when(reportRepository.countByToVersionAndStatusInAndCreatedAtAfter(
                anyString(), anyCollection(), any(Instant.class))).thenReturn(1L);
        Map<String, Object> out = service("", 5, 20)
                .check(PLATFORM, "5.4.0", CHANNEL, pteidInside(50));
        assertEquals(true, out.get("has_update"));
        assertEquals("5.4.2", out.get("latest_version"));
    }

    @Test
    void 已是最新版本时不返回更新() {
        givenReleases(release("5.4.1", 100));
        assertEquals(false, service("", 5, 20)
                .check(PLATFORM, "5.4.1", CHANNEL, pteidInside(100)).get("has_update"));
        assertEquals(false, service("", 5, 20)
                .check(PLATFORM, "5.5.0", CHANNEL, pteidInside(100)).get("has_update"));
    }
}