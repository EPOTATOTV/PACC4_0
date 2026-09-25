package com.potatotv.pacc.service.v53;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.DeviceRecord;
import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.domain.PlayerDailyPattern;
import com.potatotv.pacc.domain.PlayerHardwareFingerprint;
import com.potatotv.pacc.repository.DeviceRecordRepository;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.PlayerDailyPatternRepository;
import com.potatotv.pacc.repository.PlayerHardwareFingerprintRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v5.3 管理端只读聚合测试：指纹列表（多账号共用 / 突变标记 / 平台补齐）、信誉分布、行为趋势。
 */
class AdminInsightServiceTest {

    private PlayerHardwareFingerprintRepository fingerprints;
    private DeviceRecordRepository devices;
    private PlayerBehaviorProfileRepository profiles;
    private PlayerDailyPatternRepository dailyPatterns;
    private AdminInsightService service;

    @BeforeEach
    void setUp() {
        fingerprints = mock(PlayerHardwareFingerprintRepository.class);
        devices = mock(DeviceRecordRepository.class);
        profiles = mock(PlayerBehaviorProfileRepository.class);
        dailyPatterns = mock(PlayerDailyPatternRepository.class);
        service = new AdminInsightService(fingerprints, devices, profiles, dailyPatterns);
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksFingerprintSharedByMultipleAccounts() {
        Instant now = Instant.now();
        PlayerHardwareFingerprint a = row("p1", "hash-shared", now.minusSeconds(600), now);
        PlayerHardwareFingerprint b = row("p2", "hash-shared", now.minusSeconds(300), now.minusSeconds(60));
        PlayerHardwareFingerprint c = row("p3", "hash-solo", now.minusSeconds(120), now.minusSeconds(30));
        when(fingerprints.findTop300ByOrderByLastSeenAtDesc()).thenReturn(List.of(a, b, c));
        when(fingerprints.findByFingerprintHashIn(any(Collection.class))).thenReturn(List.of(a, b, c));
        when(fingerprints.findMutationPteids()).thenReturn(List.of("p1"));
        when(devices.findByPteidIn(any(Collection.class))).thenReturn(List.of(
                DeviceRecord.builder().deviceId("d1").pteid("p1").platform("Windows").lastLoginAt(now).build(),
                DeviceRecord.builder().deviceId("d2").pteid("p2").platform("macOS").lastLoginAt(now.minusSeconds(5)).build()));

        Map<String, Object> all = service.deviceFingerprints("", false);
        List<Map<String, Object>> items = (List<Map<String, Object>>) all.get("items");

        assertEquals(3, items.size());
        assertEquals(2, all.get("shared_total"));
        assertEquals(2, items.get(0).get("shared_count"));
        assertEquals(List.of("p1", "p2"), items.get(0).get("shared_pteids"));
        assertTrue((Boolean) items.get(0).get("shared_account"));
        assertTrue((Boolean) items.get(0).get("mutation"));
        assertEquals("Windows", items.get(0).get("platform"));
        assertFalse((Boolean) items.get(2).get("shared_account"));
        assertEquals("", items.get(2).get("platform"));

        Map<String, Object> sharedOnly = service.deviceFingerprints("", true);
        assertEquals(2, ((List<?>) sharedOnly.get("items")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void filtersFingerprintsByPlayer() {
        when(fingerprints.findTop300ByPteidOrderByLastSeenAtDesc("p9"))
                .thenReturn(List.of(row("p9", "hash-1", Instant.now(), Instant.now())));
        when(fingerprints.findByFingerprintHashIn(any(Collection.class))).thenReturn(List.of());
        when(fingerprints.findMutationPteids()).thenReturn(List.of());

        Map<String, Object> out = service.deviceFingerprints("p9", false);

        assertEquals(1, ((List<?>) out.get("items")).size());
        assertFalse((Boolean) out.get("truncated"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesReputationIntoOrderedBuckets() {
        when(profiles.count()).thenReturn(4L);
        when(profiles.summaryByLevel()).thenReturn(List.of(
                new Object[]{"TRUSTED", 1L, 950.0, 950, 950},
                new Object[]{"RISK", 2L, 400.0, 350, 450},
                new Object[]{"HIGH_RISK", 1L, 120.0, 120, 120}));

        Map<String, Object> out = service.reputationOverview();
        List<Map<String, Object>> buckets = (List<Map<String, Object>>) out.get("buckets");

        assertEquals(5, buckets.size());
        assertEquals("HIGH_RISK", buckets.get(0).get("level"));
        assertEquals("TRUSTED", buckets.get(4).get("level"));
        assertEquals(1L, buckets.get(0).get("count"));
        assertEquals(0L, buckets.get(2).get("count")); // OBSERVED 没人
        assertEquals(0.5, buckets.get(1).get("share")); // RISK 2/4
        assertEquals(468L, out.get("average_score")); // (950 + 400*2 + 120) / 4
        assertEquals(120, out.get("lowest_score"));
        assertEquals(950, out.get("highest_score"));
        assertEquals(600, out.get("initial_score"));

        Map<String, Object> trustedPolicy = (Map<String, Object>) buckets.get(4).get("sample_policy");
        assertEquals(20, trustedPolicy.get("threshold_percent_delta"));
        assertEquals(true, trustedPolicy.get("l0_only"));

        assertEquals(5, ((List<?>) out.get("rules")).size());
        assertEquals(200, out.get("clean_hour_bonus_cap"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void buildsTrendWithinRequestedWindow() {
        LocalDate today = LocalDate.now();
        when(dailyPatterns.findByPteidOrderByPatternDateDescHourOfDayAsc("p1")).thenReturn(List.of(
                pattern("p1", today, 21, 1800, 3),
                pattern("p1", today, 20, 600, 1),
                pattern("p1", today.minusDays(1), 10, 3600, 5),
                pattern("p1", today.minusDays(80), 10, 9999, 99)));
        PlayerBehaviorProfile profile = PlayerBehaviorProfile.builder().pteid("p1").meanCps(7.5).sampleCount(42L).build();
        when(profiles.findById("p1")).thenReturn(Optional.of(profile));

        Map<String, Object> out = service.behaviorTrend("p1", 30);
        List<Map<String, Object>> daily = (List<Map<String, Object>>) out.get("daily");
        List<Map<String, Object>> hourly = (List<Map<String, Object>>) out.get("hourly");
        Map<String, Object> baseline = (Map<String, Object>) out.get("baseline");

        assertEquals(30, out.get("days"));
        assertEquals(2, daily.size(), "超出窗口的作息不应计入");
        assertEquals(today.minusDays(1).toString(), daily.get(0).get("date"));
        assertEquals(2400L, daily.get(1).get("session_seconds"));
        assertEquals(0.67, daily.get(1).get("online_hours"));
        assertEquals(4L, daily.get(1).get("event_count"));
        assertEquals(24, hourly.size());
        assertEquals(1.0, hourly.get(10).get("online_hours"));
        assertEquals(600L, hourly.get(20).get("session_seconds"));
        assertEquals(1800L, hourly.get(21).get("session_seconds"));
        assertEquals(true, baseline.get("exists"));
        assertEquals(7.5, baseline.get("mean_cps"));
        assertEquals(42L, baseline.get("sample_count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void trendWithoutProfileReturnsEmptySeries() {
        Map<String, Object> out = service.behaviorTrend("nobody", 30);

        assertTrue(((List<?>) out.get("daily")).isEmpty());
        assertEquals(24, ((List<?>) out.get("hourly")).size());
        assertEquals(false, ((Map<String, Object>) out.get("baseline")).get("exists"));
    }

    private static PlayerHardwareFingerprint row(String pteid, String hash, Instant first, Instant last) {
        return PlayerHardwareFingerprint.builder()
                .pteid(pteid).fingerprintHash(hash).firstSeenAt(first).lastSeenAt(last).seenCount(1L).build();
    }

    private static PlayerDailyPattern pattern(String pteid, LocalDate date, int hour, long seconds, long events) {
        return PlayerDailyPattern.builder()
                .pteid(pteid).patternDate(date).hourOfDay(hour).sessionSeconds(seconds).eventCount(events).build();
    }
}