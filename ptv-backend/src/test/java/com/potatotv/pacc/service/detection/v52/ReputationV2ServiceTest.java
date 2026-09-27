package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.domain.ReputationLog;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.V52ReputationLogRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * v5.2 §7.2 信誉系统确定性单测：初始 600、五条规则各按文档分值生效、无检测小时奖励封顶 +200、
 * 分值裁剪到 [0,1000]、等级边界精确、按来源事件 id 幂等。
 */
class ReputationV2ServiceTest {

    private static final String PTEID = "PT1";

    private PlayerBehaviorProfileRepository profileRepo;
    private V52ReputationLogRepository logRepo;
    private ReputationV2Service service;

    /** 内存替身：画像与审计行。 */
    private final Map<String, PlayerBehaviorProfile> profiles = new HashMap<>();
    private final List<ReputationLog> logs = new ArrayList<>();

    @BeforeEach
    void setUp() {
        profileRepo = mock(PlayerBehaviorProfileRepository.class);
        logRepo = mock(V52ReputationLogRepository.class);
        service = new ReputationV2Service(profileRepo, logRepo);

        when(profileRepo.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(profiles.get(inv.getArgument(0, String.class))));
        when(profileRepo.save(any(PlayerBehaviorProfile.class))).thenAnswer(inv -> {
            PlayerBehaviorProfile p = inv.getArgument(0);
            profiles.put(p.getPteid(), p);
            return p;
        });
        when(logRepo.save(any(ReputationLog.class))).thenAnswer(inv -> {
            ReputationLog l = inv.getArgument(0);
            logs.add(l);
            return l;
        });
        when(logRepo.existsBySource(anyString()))
                .thenAnswer(inv -> logs.stream().anyMatch(l -> inv.getArgument(0, String.class).equals(l.getSource())));
        when(logRepo.countByPteidAndSourceStartingWith(anyString(), anyString()))
                .thenAnswer(inv -> logs.stream()
                        .filter(l -> inv.getArgument(0, String.class).equals(l.getPteid()))
                        .filter(l -> l.getSource() != null
                                && l.getSource().startsWith(inv.getArgument(1, String.class)))
                        .count());
        when(logRepo.findByPteidAndSourceStartingWithOrderByCreatedAtDesc(anyString(), anyString(), any()))
                .thenReturn(List.of());
    }

    // ------------------------------ 初始分与规则 ------------------------------

    @Test
    void unknownPlayerStartsAtSixHundred() {
        assertEquals(ReputationV2Service.INITIAL_SCORE, service.score(PTEID));
        assertEquals(600, ReputationV2Service.INITIAL_SCORE);
        assertEquals(PlayerBehaviorProfile.LEVEL_OBSERVED, ReputationV2Service.levelFor(600));
        assertEquals(60, ReputationV2Service.legacyScale(600));
    }

    @Test
    void cleanHourAddsOnePerHour() {
        assertTrue(service.apply(PTEID, ReputationV2Service.Event.CLEAN_HOUR, "h1", null));
        assertTrue(service.apply(PTEID, ReputationV2Service.Event.CLEAN_HOUR, "h2", null));
        assertEquals(602, service.score(PTEID));
    }

    @Test
    void cleanHourBonusIsCappedAtTwoHundred() {
        for (int i = 0; i < 200; i++) {
            assertTrue(service.apply(PTEID, ReputationV2Service.Event.CLEAN_HOUR, "h" + i, null));
        }
        assertEquals(800, service.score(PTEID));
        // 第 201 小时已达累计上限，不再记账
        assertFalse(service.apply(PTEID, ReputationV2Service.Event.CLEAN_HOUR, "h201", null));
        assertEquals(800, service.score(PTEID));
        assertEquals(200, logs.size());
    }

    @Test
    void confirmedRedscreenSubtractsFifty() {
        service.apply(PTEID, ReputationV2Service.Event.CONFIRMED_REDSCREEN, "alert-1", null);
        assertEquals(550, service.score(PTEID));
    }

    @Test
    void approvedAppealAddsTwenty() {
        service.apply(PTEID, ReputationV2Service.Event.APPEAL_APPROVED, "appeal-1", null);
        assertEquals(620, service.score(PTEID));
    }

    @Test
    void fingerprintMutationSubtractsThirtyAndNewDeviceSubtractsTen() {
        service.apply(PTEID, ReputationV2Service.Event.FINGERPRINT_MUTATION, "fp-b", null);
        assertEquals(570, service.score(PTEID));
        service.apply(PTEID, ReputationV2Service.Event.NEW_DEVICE_LOGIN, "fp-a", null);
        assertEquals(560, service.score(PTEID));
    }

    @Test
    void scoreIsClampedAtBothEnds() {
        for (int i = 0; i < 20; i++) {
            service.apply(PTEID, ReputationV2Service.Event.CONFIRMED_REDSCREEN, "alert-" + i, null);
        }
        assertEquals(0, service.score(PTEID));
        assertTrue(service.apply(PTEID, ReputationV2Service.Event.CONFIRMED_REDSCREEN, "alert-clamp", null));
        assertEquals(0, service.score(PTEID));

        assertTrue(service.adjustManual(PTEID, 5000, "补偿", "admin"));
        assertEquals(1000, service.score(PTEID));
        assertEquals(PlayerBehaviorProfile.LEVEL_TRUSTED, profiles.get(PTEID).getReputationLevel());
        assertEquals(100, ReputationV2Service.legacyScale(1000));
    }

    @Test
    void deltasAlwaysMatchTheAuditRowDelta() {
        service.apply(PTEID, ReputationV2Service.Event.CONFIRMED_REDSCREEN, "alert-d", null);
        ReputationLog last = logs.get(0);
        assertEquals(-50, last.getDelta());
        assertEquals(550, last.getScoreAfter());
        assertTrue(last.getSource().startsWith(ReputationV2Service.SOURCE_PREFIX));
    }

    // ------------------------------ 等级边界 ------------------------------

    @Test
    void levelBoundariesAreExact() {
        assertEquals(PlayerBehaviorProfile.LEVEL_HIGH_RISK, ReputationV2Service.levelFor(299));
        assertEquals(PlayerBehaviorProfile.LEVEL_RISK, ReputationV2Service.levelFor(300));
        assertEquals(PlayerBehaviorProfile.LEVEL_RISK, ReputationV2Service.levelFor(499));
        assertEquals(PlayerBehaviorProfile.LEVEL_OBSERVED, ReputationV2Service.levelFor(500));
        assertEquals(PlayerBehaviorProfile.LEVEL_OBSERVED, ReputationV2Service.levelFor(699));
        assertEquals(PlayerBehaviorProfile.LEVEL_NORMAL, ReputationV2Service.levelFor(700));
        assertEquals(PlayerBehaviorProfile.LEVEL_NORMAL, ReputationV2Service.levelFor(899));
        assertEquals(PlayerBehaviorProfile.LEVEL_TRUSTED, ReputationV2Service.levelFor(900));
        assertEquals(PlayerBehaviorProfile.LEVEL_TRUSTED, ReputationV2Service.levelFor(1000));
    }

    // ------------------------------ 幂等 ------------------------------

    @Test
    void sameEventIdIsCreditedOnlyOnce() {
        assertTrue(service.apply(PTEID, ReputationV2Service.Event.APPEAL_APPROVED, "appeal-42", null));
        assertFalse(service.apply(PTEID, ReputationV2Service.Event.APPEAL_APPROVED, "appeal-42", null));
        assertEquals(620, service.score(PTEID));
        assertEquals(1, logs.size());

        // 不同事件 id 仍可继续加分
        assertTrue(service.apply(PTEID, ReputationV2Service.Event.APPEAL_APPROVED, "appeal-43", null));
        assertEquals(640, service.score(PTEID));
    }

    @Test
    void distinctRedAlertIdsEachApplyOnce() {
        assertTrue(service.apply(PTEID, ReputationV2Service.Event.CONFIRMED_REDSCREEN, "alert-a", null));
        assertFalse(service.apply(PTEID, ReputationV2Service.Event.CONFIRMED_REDSCREEN, "alert-a", null));
        assertTrue(service.apply(PTEID, ReputationV2Service.Event.CONFIRMED_REDSCREEN, "alert-b", null));
        assertEquals(500, service.score(PTEID));
    }

    @Test
    void manualAdjustmentRequiresReasonAndIsAlwaysRecorded() {
        assertThrows(IllegalArgumentException.class,
                () -> service.adjustManual(PTEID, 10, "  ", "admin"));
        assertTrue(service.adjustManual(PTEID, 10, "客服补偿", "admin"));
        assertTrue(service.adjustManual(PTEID, 10, "客服补偿", "admin"));
        assertEquals(620, service.score(PTEID));
        assertEquals(2, logs.size());
    }

    @Test
    void detailExposesScoreLevelAndPolicy() {
        Map<String, Object> detail = service.detail(PTEID, 10);
        assertEquals(600, detail.get("score"));
        assertEquals(1000, detail.get("max_score"));
        assertEquals(PlayerBehaviorProfile.LEVEL_OBSERVED, detail.get("level"));
        assertEquals(60, detail.get("legacy_scale_score"));
        @SuppressWarnings("unchecked")
        Map<String, Object> policy = (Map<String, Object>) detail.get("policy");
        assertEquals(-10, policy.get("threshold_percent_delta"));
        assertEquals(0.9, (Double) policy.get("threshold_multiplier"), 1e-9);
        assertFalse((Boolean) policy.get("l0_only"));
    }
}