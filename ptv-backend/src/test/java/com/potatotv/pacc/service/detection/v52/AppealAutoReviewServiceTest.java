package com.potatotv.pacc.service.detection.v52;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.PlayerBehaviorProfile;
import com.potatotv.pacc.domain.ZeroDayFinding;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.PlayerBehaviorProfileRepository;
import com.potatotv.pacc.repository.ZeroDayFindingRepository;
import com.potatotv.pacc.service.AppealService;
import com.potatotv.pacc.service.ConfidenceService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * v5.2 §7.4 申诉自动复核测试：复评分构成 → 三类结论（误报自动撤销 / 维持转人工 / 证据不足转人工）、
 * 样本回流落库、信誉加分与幂等边界。
 */
class AppealAutoReviewServiceTest {

    private AppealRepository appeals;
    private AppealService appealService;
    private CheatRecordRepository cheatRecords;
    private ZeroDayFindingRepository zeroDayFindings;
    private PlayerBehaviorProfileRepository profiles;
    private ReputationV2Service reputationV2;
    private AppealAutoReviewService service;

    @BeforeEach
    void setUp() {
        appeals = mock(AppealRepository.class);
        appealService = mock(AppealService.class);
        cheatRecords = mock(CheatRecordRepository.class);
        zeroDayFindings = mock(ZeroDayFindingRepository.class);
        profiles = mock(PlayerBehaviorProfileRepository.class);
        reputationV2 = mock(ReputationV2Service.class);
        service = new AppealAutoReviewService(appeals, appealService, cheatRecords, zeroDayFindings,
                profiles, reputationV2, new ConfidenceService(70, 85));
        when(reputationV2.score(anyString())).thenReturn(ReputationV2Service.INITIAL_SCORE);
        when(profiles.findById(anyString())).thenReturn(Optional.empty());
        when(zeroDayFindings.findByPteidAndCreatedAtAfterOrderByCreatedAtDesc(anyString(), any(Instant.class)))
                .thenReturn(List.of());
        when(appealService.applyAutoReview(anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(inv -> appeal(inv.getArgument(0)));
    }

    @Test
    void weakEvidenceAndCleanHistoryAutoRevokesAsMisreport() {
        stubRecord(20, false);

        AppealAutoReviewService.Assessment assessment = service.assess(appeal("a1"));

        assertEquals("MISREPORT", assessment.verdict(), "低原判 + 无零日信号 + 高信誉应判误报");
        assertTrue(assessment.score() <= AppealAutoReviewService.MISREPORT_MAX);

        AppealAutoReviewService.Outcome outcome = service.review(appeal("a1"));

        assertEquals("MISREPORT", outcome.verdict());
        verify(appealService).applyAutoReview(eq("a1"), eq("MISREPORT"), anyInt(), anyString());
        verify(reputationV2).apply(eq("PT1"), eq(ReputationV2Service.Event.APPEAL_APPROVED), eq("a1"), anyString());
    }

    @Test
    void strongEvidenceAndConfirmedZeroDayStaysAndGoesToAnalyst() {
        stubRecord(96, false);
        stubZeroDay(95, Boolean.TRUE);
        when(reputationV2.score("PT1")).thenReturn(200);
        when(profiles.findById("PT1")).thenReturn(Optional.of(profile(10, 0)));

        AppealAutoReviewService.Outcome outcome = service.review(appeal("a2"));

        assertEquals("CONFIRMED", outcome.verdict(), "强原判 + 已确认零日 + 低信誉应维持");
        verify(appealService).applyAutoReview(eq("a2"), eq("CONFIRMED"), anyInt(), anyString());
        verify(reputationV2, never()).apply(anyString(), any(), anyString(), anyString());
    }

    @Test
    void middleGroundGoesToHumanQueue() {
        stubRecord(60, false);

        AppealAutoReviewService.Outcome outcome = service.review(appeal("a3"));

        assertEquals("INCONCLUSIVE", outcome.verdict());
        verify(appealService).applyAutoReview(eq("a3"), eq("INCONCLUSIVE"), anyInt(), anyString());
    }

    @Test
    void revokedRecordCannotJustifyKeepingTheVerdict() {
        stubRecord(99, true);

        AppealAutoReviewService.Assessment assessment = service.assess(appeal("a4"));

        assertTrue(assessment.evidence() <= AppealAutoReviewService.MISREPORT_MAX,
                "已撤销的记录不应再作为维持依据，实际证据分 " + assessment.evidence());
    }

    @Test
    void confirmedMisreportZeroDaySuppressesSignal() {
        stubRecord(90, false);
        stubZeroDay(95, Boolean.FALSE);

        AppealAutoReviewService.Assessment assessment = service.assess(appeal("a5"));

        assertTrue(assessment.zeroDay() <= 20, "已确认误报的零日发现应压低信号，实际 " + assessment.zeroDay());
    }

    @Test
    void humanConfirmedZeroDayRaisesSignalFloor() {
        stubRecord(50, false);
        stubZeroDay(40, Boolean.TRUE);

        AppealAutoReviewService.Assessment assessment = service.assess(appeal("a6"));

        assertTrue(assessment.zeroDay() >= 90, "已确认为真样本的零日发现应抬高信号");
    }

    @Test
    void conclusionsAreRecycledAsTrainingSamples() {
        stubRecord(96, false);
        stubZeroDay(95, Boolean.TRUE);

        service.review(appeal("a7"));

        ArgumentCaptor<ZeroDayFinding> captor = ArgumentCaptor.forClass(ZeroDayFinding.class);
        verify(zeroDayFindings).save(captor.capture());
        ZeroDayFinding sample = captor.getValue();
        assertEquals(ZeroDayFinding.Status.REVIEWED, sample.getStatus());
        assertEquals("ai-appeal", sample.getReviewer());
        assertTrue(Boolean.TRUE.equals(sample.getConfirmed()), "维持原判的回流样本 confirmed=true");
        assertFalse(sample.getReviewComment().isBlank());
    }

    @Test
    void misreportRecyclesAsFalseFindingAndSkipsWhenAlreadyReviewed() {
        stubRecord(10, false);
        stubZeroDay(20, Boolean.FALSE);

        service.review(appeal("a8"));

        ArgumentCaptor<ZeroDayFinding> captor = ArgumentCaptor.forClass(ZeroDayFinding.class);
        verify(zeroDayFindings).save(captor.capture());
        assertTrue(Boolean.FALSE.equals(captor.getValue().getConfirmed()), "误报样本 confirmed=false");

        // 已复核过的申诉不再处理
        Appeal done = appeal("a9");
        done.setAutoReview("MISREPORT");
        assertNull(service.review(done));
    }

    @Test
    void noFeaturesMeansNoRecycledSample() {
        stubRecord(10, false);
        // 零日发现没有特征摘要：不写回流样本，避免污染训练集
        when(zeroDayFindings.findByPteidAndCreatedAtAfterOrderByCreatedAtDesc(anyString(), any(Instant.class)))
                .thenReturn(List.of(ZeroDayFinding.builder().id("z1").pteid("PT1")
                        .compositeScore(30).featuresJson("  ").build()));

        AppealAutoReviewService.Outcome outcome = service.review(appeal("a10"));

        assertNull(outcome.recycledFindingId());
        verify(zeroDayFindings, never()).save(any(ZeroDayFinding.class));
    }

    @Test
    void falsePositiveHistoryLowersScore() {
        stubRecord(60, false);
        when(profiles.findById("PT1")).thenReturn(Optional.of(profile(100, 50)));

        AppealAutoReviewService.Assessment generous = service.assess(appeal("a11"));

        when(profiles.findById("PT1")).thenReturn(Optional.of(profile(100, 0)));
        AppealAutoReviewService.Assessment strict = service.assess(appeal("a12"));

        assertTrue(generous.score() < strict.score(), "历史误报率高的玩家复评分应更低");
    }

    // ------------------------------ 测试数据 ------------------------------

    private void stubRecord(int riskScore, boolean revoked) {
        when(cheatRecords.findFirstByAlertId(anyString())).thenReturn(Optional.of(CheatRecord.builder()
                .recordId("r1").alertId("alert_1").pteid("PT1").cheatType("killaura")
                .riskScore(riskScore).revoked(revoked).build()));
    }

    private void stubZeroDay(int composite, Boolean confirmed) {
        when(zeroDayFindings.findByPteidAndCreatedAtAfterOrderByCreatedAtDesc(anyString(), any(Instant.class)))
                .thenReturn(List.of(ZeroDayFinding.builder()
                        .id("z1").pteid("PT1").edition("JAVA").compositeScore(composite)
                        .featuresJson("{\"feature_click_cps\": 18.0}")
                        .confirmed(confirmed).build()));
    }

    private static PlayerBehaviorProfile profile(long detections, long falsePositives) {
        return PlayerBehaviorProfile.builder()
                .pteid("PT1").totalDetections(detections).falsePositives(falsePositives).build();
    }

    private static Appeal appeal(String id) {
        return Appeal.builder()
                .appealId(id).pteid("PT1").alertId("alert_1").reason("误报申诉")
                .status("pending").autoReview("PENDING").prescreenScore(0)
                .createdAt(Instant.now())
                .build();
    }
}