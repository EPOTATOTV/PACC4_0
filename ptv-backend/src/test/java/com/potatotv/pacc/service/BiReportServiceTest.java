package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.AdminLoginLogRepository;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BiReportServiceTest {

    private DetectionEventRepository eventRepository;
    private RedscreenAlertRepository alertRepository;
    private CheatRecordRepository cheatRecordRepository;
    private AccountRepository accountRepository;
    private AppealRepository appealRepository;
    private AdminLoginLogRepository adminLoginLogRepository;
    private BiReportService bi;

    @BeforeEach
    void setup() {
        eventRepository = mock(DetectionEventRepository.class);
        alertRepository = mock(RedscreenAlertRepository.class);
        cheatRecordRepository = mock(CheatRecordRepository.class);
        accountRepository = mock(AccountRepository.class);
        appealRepository = mock(AppealRepository.class);
        adminLoginLogRepository = mock(AdminLoginLogRepository.class);
        bi = new BiReportService(eventRepository, alertRepository, cheatRecordRepository,
                accountRepository, appealRepository, adminLoginLogRepository);
    }

    @Test
    void detectionTrendAggregatesByDay() {
        Instant now = Instant.now();
        // 今天 + 前天各 2 条，昨天 0
        when(eventRepository.countGroupByDay(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        new Object[]{now.minus(2, ChronoUnit.DAYS), 2L},
                        new Object[]{now, 2L}));
        Map<String, Object> r = bi.detectionTrend(7, null, null);
        assertEquals(4L, ((Number) r.get("total")).longValue());
        assertEquals(7, ((List<?>) r.get("days")).size(), "近 7 天应补齐空日");
        @SuppressWarnings("unchecked")
        List<Long> counts = (List<Long>) r.get("counts");
        assertEquals(2, counts.get(6).longValue(), "今天计数");
        assertEquals(0, counts.get(0).longValue(), "最早一天为空");
    }

    @Test
    void redscreenHealthComputesFpRate() {
        when(alertRepository.countGroupByState(any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(
                        new Object[]{"CONFIRMED", 30L},
                        new Object[]{"FALSE_POSITIVE", 10L}));
        Map<String, Object> r = bi.redscreenHealth(Instant.now().minus(7, ChronoUnit.DAYS), Instant.now());
        assertEquals(40L, ((Number) r.get("total")).longValue());
        assertEquals(25.0, ((Number) r.get("false_positive_rate")).doubleValue(), 0.01, "误报率 10/40=25%");
    }

    @Test
    void redscreenHealthZeroTotalYieldsZeroRate() {
        when(alertRepository.countGroupByState(any(Instant.class), any(Instant.class))).thenReturn(List.of());
        Map<String, Object> r = bi.redscreenHealth(Instant.now().minus(1, ChronoUnit.DAYS), Instant.now());
        assertEquals(0L, ((Number) r.get("total")).longValue());
        assertEquals(0.0, ((Number) r.get("false_positive_rate")).doubleValue(), 0.01);
    }

    @Test
    void playerProfileBucketsReputationAndStatus() {
        when(accountRepository.count()).thenReturn(10L);
        when(accountRepository.countByReputationBetween(eq(0), eq(49))).thenReturn(1L);
        when(accountRepository.countByReputationBetween(eq(50), eq(69))).thenReturn(2L);
        when(accountRepository.countByReputationBetween(eq(70), eq(84))).thenReturn(3L);
        when(accountRepository.countByReputationBetween(eq(85), eq(100))).thenReturn(4L);
        when(accountRepository.countByStatus("normal")).thenReturn(9L);
        when(accountRepository.countByStatus("suspicious")).thenReturn(1L);
        when(accountRepository.countByStatus("high_risk")).thenReturn(0L);
        when(accountRepository.countByStatus("locked_inspect")).thenReturn(0L);

        Map<String, Object> r = bi.playerProfile();
        assertEquals(10L, ((Number) r.get("total")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> rep = (Map<String, Object>) r.get("reputation");
        assertEquals(2L, ((Number) rep.get("50_69")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> status = (Map<String, Object>) r.get("status");
        assertEquals(1L, ((Number) status.get("suspicious")).longValue());
    }

    @Test
    void editionSplitCountsPerEdition() {
        when(eventRepository.countByEditionAndOccurredAtBetween(eq(DetectionEvent.Edition.BEDROCK), any(), any())).thenReturn(5L);
        when(eventRepository.countByEditionAndOccurredAtBetween(eq(DetectionEvent.Edition.JAVA), any(), any())).thenReturn(7L);
        Map<String, Object> r = bi.editionSplit(Instant.now().minus(7, ChronoUnit.DAYS), Instant.now());
        assertEquals(5L, ((Number) r.get("bedrock")).longValue());
        assertEquals(7L, ((Number) r.get("java")).longValue());
    }

    @Test
    void loginAuditBuildsOkFailSeries() {
        when(adminLoginLogRepository.countGroupByDayAndResult(any(), any()))
                .thenReturn(List.of(
                        new Object[]{Instant.now(), "success", 4L},
                        new Object[]{Instant.now(), "fail", 1L}));
        Map<String, Object> r = bi.loginAudit(7, null, null);
        @SuppressWarnings("unchecked")
        List<Long> ok = (List<Long>) r.get("success");
        assertTrue(ok.get(ok.size() - 1).longValue() >= 4, "今天成功登录计数");
    }

    @Test
    void cheatRecordHealthReportsPersistedAndRevoked() {
        when(cheatRecordRepository.countByRevokedFalse()).thenReturn(20L);
        when(cheatRecordRepository.countRevokedTrue()).thenReturn(5L);
        Map<String, Object> r = bi.cheatRecordHealth();
        assertEquals(20L, ((Number) r.get("persisted")).longValue());
        assertEquals(5L, ((Number) r.get("revoked")).longValue());
        assertEquals(25L, ((Number) r.get("total")).longValue());
    }
}