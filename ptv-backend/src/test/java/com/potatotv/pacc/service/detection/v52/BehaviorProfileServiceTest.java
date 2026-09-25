package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.domain.PlayerDailyPattern;
import com.potatotv.pacc.domain.PlayerHardwareFingerprint;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.PlayerDailyPatternRepository;
import com.potatotv.pacc.repository.PlayerHardwareFingerprintRepository;
import com.potatotv.pacc.service.detection.v46.ZeroDayAnomalyService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §6.2 行为画像确定性单测：Welford 在线统计收敛、稳定度在 10 小时观测处达到 1.0、
 * 新玩家 / 老玩家的自适应阈值倍率序、硬件指纹突变标记与设备新增判定。
 */
class BehaviorProfileServiceTest {

    private static final String PTEID = "PT1";

    private PlayerBehaviorProfileRepository profileRepo;
    private PlayerDailyPatternRepository patternRepo;
    private PlayerHardwareFingerprintRepository fingerprintRepo;
    private ZeroDayAnomalyService zeroDayService;

    private BehaviorProfileService service;

    /** 内存替身：findById 读、save 写，模拟持久层。 */
    private final Map<String, PlayerBehaviorProfile> profiles = new HashMap<>();
    private final Map<String, PlayerDailyPattern> patterns = new HashMap<>();
    private PlayerDailyPattern savedPattern;

    @BeforeEach
    void setUp() {
        profileRepo = mock(PlayerBehaviorProfileRepository.class);
        patternRepo = mock(PlayerDailyPatternRepository.class);
        fingerprintRepo = mock(PlayerHardwareFingerprintRepository.class);
        zeroDayService = mock(ZeroDayAnomalyService.class);
        service = new BehaviorProfileService(profileRepo, patternRepo, fingerprintRepo, zeroDayService);

        when(profileRepo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(profiles.get(inv.getArgument(0, String.class))));
        when(profileRepo.save(any(PlayerBehaviorProfile.class))).thenAnswer(inv -> {
            PlayerBehaviorProfile p = inv.getArgument(0);
            profiles.put(p.getPteid(), p);
            return p;
        });
        when(profileRepo.findAll()).thenAnswer(inv -> new ArrayList<>(profiles.values()));
        when(patternRepo.findByPteidAndPatternDateAndHourOfDay(anyString(), any(LocalDate.class), anyInt()))
                .thenAnswer(inv -> Optional.ofNullable(patterns.get(
                        inv.getArgument(0, String.class) + "|" + inv.getArgument(1, LocalDate.class)
                                + "|" + inv.getArgument(2, Integer.class))));
        when(patternRepo.save(any(PlayerDailyPattern.class))).thenAnswer(inv -> {
            PlayerDailyPattern row = inv.getArgument(0);
            patterns.put(row.getPteid() + "|" + row.getPatternDate() + "|" + row.getHourOfDay(), row);
            savedPattern = row;
            return row;
        });
        when(zeroDayService.assess(anyString(), anyString(), any(FeatureVector.class)))
                .thenReturn(Map.of("finding_id", "finding-1"));
    }

    // ------------------------------ 在线统计 ------------------------------

    @Test
    void onlineStatsConvergeToPopulationMeanAndStd() {
        // 50 个 5.0 + 50 个 15.0 → 均值 10，总体标准差 5
        for (int i = 0; i < 100; i++) {
            service.observe(PTEID, new FeatureVector().set("feature_click_cps", i < 50 ? 5.0 : 15.0), false);
        }
        PlayerBehaviorProfile p = profiles.get(PTEID);
        assertEquals(10.0, p.getMeanCps(), 1e-9);
        assertEquals(5.0, p.getCpsStd(), 1e-9);
        assertEquals(100L, p.getSampleCount());
    }

    @Test
    void observeUpdatesTrackedFeaturesIndependentlyAndCountsSessions() {
        service.observe(PTEID, new FeatureVector()
                .set("feature_click_cps", 6.0)
                .set("feature_aim_smoothness", 0.4)
                .set("feature_speed_ratio", 1.02), true);
        service.observe(PTEID, new FeatureVector()
                .set("feature_click_cps", 8.0)
                .set("feature_aim_smoothness", 0.6)
                .set("feature_speed_ratio", 0.98), false);

        PlayerBehaviorProfile p = profiles.get(PTEID);
        assertEquals(7.0, p.getMeanCps(), 1e-9);
        assertEquals(1.0, p.getCpsStd(), 1e-9);
        assertEquals(0.5, p.getMeanAimSmoothness(), 1e-9);
        assertEquals(1.0, p.getMeanSpeed(), 1e-6);
        assertEquals(2L, p.getSampleCount());
        assertEquals(1L, p.getTotalSessions());
        // 作息明细按「日 + 小时」累加：30 秒/条观测
        assertEquals(2L, savedPattern.getEventCount());
        assertEquals(2L * BehaviorProfileService.OBSERVATION_SECONDS, savedPattern.getSessionSeconds());
    }

    @Test
    void observeIgnoresEmptyVectorAndBlankPteid() {
        service.observe("", new FeatureVector().set("feature_click_cps", 5.0), false);
        service.observe(PTEID, new FeatureVector(), false);
        assertTrue(profiles.isEmpty());
        verify(profileRepo, never()).save(any(PlayerBehaviorProfile.class));
    }

    // ------------------------------ 稳定度 ------------------------------

    @Test
    void stabilityReachesOneNearTenHoursOfObservedPlay() {
        // 10 小时 = 36000 秒，按 30 秒/条观测 = 1200 条
        assertEquals(0.0, BehaviorProfileService.stabilityOf(0), 1e-9);
        assertEquals(0.5, BehaviorProfileService.stabilityOf(600), 1e-9);
        assertEquals(1.0, BehaviorProfileService.stabilityOf(1200), 1e-9);
        assertEquals(1.0, BehaviorProfileService.stabilityOf(5000), 1e-9);
    }

    @Test
    void observeDrivesStabilityUpToConvergence() {
        for (int i = 0; i < 1200; i++) {
            service.observe(PTEID, new FeatureVector().set("feature_click_cps", 7.0), false);
        }
        assertEquals(1.0, profiles.get(PTEID).getStability(), 1e-9);
    }

    // ------------------------------ 自适应阈值 ------------------------------

    @Test
    void adaptiveMultiplierOrdersNewPlayerNormalVeteran() {
        double newPlayer = service.adaptiveThresholdMultiplier(PTEID);          // 无画像 → 最敏感
        profiles.put(PTEID, profileWith(100L, 0L));                             // 50 分钟 → 新玩家
        double stillNew = service.adaptiveThresholdMultiplier(PTEID);
        profiles.put(PTEID, profileWith(2000L, 0L));                            // ~16.7 小时 → 常规
        double normal = service.adaptiveThresholdMultiplier(PTEID);
        profiles.put(PTEID, profileWith(12500L, 0L));                           // >100 小时且零误报 → 宽松
        double veteran = service.adaptiveThresholdMultiplier(PTEID);

        assertEquals(BehaviorProfileService.NEW_PLAYER_MULTIPLIER, newPlayer, 1e-9);
        assertEquals(BehaviorProfileService.NEW_PLAYER_MULTIPLIER, stillNew, 1e-9);
        assertEquals(BehaviorProfileService.NORMAL_MULTIPLIER, normal, 1e-9);
        assertEquals(BehaviorProfileService.VETERAN_MULTIPLIER, veteran, 1e-9);
        assertTrue(newPlayer < normal && normal < veteran);
    }

    @Test
    void veteranWithFalsePositivesIsNotLenient() {
        profiles.put(PTEID, profileWith(12500L, 1L));
        assertEquals(BehaviorProfileService.NORMAL_MULTIPLIER,
                service.adaptiveThresholdMultiplier(PTEID), 1e-9);
    }

    @Test
    void fingerprintMutationMarksSuspiciousAndTightens() {
        profiles.put(PTEID, profileWith(12500L, 0L));
        when(fingerprintRepo.countByPteid(PTEID)).thenReturn(2L);

        assertTrue(service.hasDeviceMutation(PTEID));
        assertEquals(BehaviorProfileService.SUSPICIOUS_MULTIPLIER,
                service.adaptiveThresholdMultiplier(PTEID), 1e-9);
    }

    @Test
    void deviceFingerprintSeenReportsNewDeviceOnlyOnce() {
        when(fingerprintRepo.findByPteidAndFingerprintHash(PTEID, "hash-a"))
                .thenReturn(Optional.empty());
        assertTrue(service.deviceFingerprintSeen(PTEID, "hash-a"));
        verify(fingerprintRepo, times(1)).save(any(PlayerHardwareFingerprint.class));

        PlayerHardwareFingerprint known = PlayerHardwareFingerprint.builder()
                .pteid(PTEID).fingerprintHash("hash-a").seenCount(1L).build();
        when(fingerprintRepo.findByPteidAndFingerprintHash(PTEID, "hash-a"))
                .thenReturn(Optional.of(known));
        assertFalse(service.deviceFingerprintSeen(PTEID, "hash-a"));
        assertEquals(2L, known.getSeenCount());
    }

    // ------------------------------ 行为突变与检测计数 ------------------------------

    @Test
    void patternMutationTriggersZeroDayCheck() {
        // 60 条基线：cps 在 9/11 之间抖动 → 均值 10，标准差 1
        for (int i = 0; i < 60; i++) {
            service.observe(PTEID, new FeatureVector().set("feature_click_cps", i % 2 == 0 ? 9.0 : 11.0), false);
        }
        service.observe(PTEID, new FeatureVector().set("feature_click_cps", 50.0), false);

        verify(zeroDayService, times(1)).assess(eq(PTEID), anyString(), any(FeatureVector.class));
    }

    @Test
    void noMutationBeforeProfileIsEstablished() {
        for (int i = 0; i < 5; i++) {
            service.observe(PTEID, new FeatureVector().set("feature_click_cps", 9.0 + i), false);
        }
        service.observe(PTEID, new FeatureVector().set("feature_click_cps", 999.0), false);
        verify(zeroDayService, never()).assess(anyString(), anyString(), any(FeatureVector.class));
    }

    @Test
    void detectionObservationCountsFalsePositives() {
        profiles.put(PTEID, profileWith(200L, 0L));
        assertTrue(service.recordObservationOfDetection(PTEID, true));
        assertTrue(service.recordObservationOfDetection(PTEID, false));
        PlayerBehaviorProfile p = profiles.get(PTEID);
        assertEquals(2L, p.getTotalDetections());
        assertEquals(1L, p.getFalsePositives());
        assertFalse(service.recordObservationOfDetection("PT-unknown", true));
    }

    @Test
    void nightlyRebuildSyncsStabilityAndLevelAndIsSafeWhenEmpty() {
        PlayerBehaviorProfile p = profileWith(1200L, 0L);
        p.setStability(0.0);
        p.setReputationLevel(PlayerBehaviorProfile.LEVEL_OBSERVED);
        p.setReputationScore(950);
        profiles.put(PTEID, p);
        when(patternRepo.deleteByPatternDateBefore(any(LocalDate.class))).thenReturn(3L);

        Map<String, Object> result = service.rebuildProfiles();
        assertEquals(1, result.get("profiles"));
        assertEquals(1, result.get("rebuilt"));
        assertEquals(3L, result.get("daily_patterns_compacted"));
        assertEquals(1.0, p.getStability(), 1e-9);
        assertEquals(PlayerBehaviorProfile.LEVEL_TRUSTED, p.getReputationLevel());

        profiles.clear();
        assertEquals(0, service.rebuildProfiles().get("profiles"));
    }

    private static PlayerBehaviorProfile profileWith(long sampleCount, long falsePositives) {
        return PlayerBehaviorProfile.builder()
                .pteid(PTEID)
                .sampleCount(sampleCount)
                .falsePositives(falsePositives)
                .reputationScore(PlayerBehaviorProfile.SCORE_INITIAL)
                .reputationLevel(PlayerBehaviorProfile.LEVEL_OBSERVED)
                .firstSeenAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }
}