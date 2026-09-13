package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 误报申诉服务确定性单测：自动初筛、证据快照、审批恢复信誉与撤销作弊记录、驳回撤销。
 */
class AppealServiceTest {

    private AppealRepository appealRepo;
    private CheatRecordRepository cheatRepo;
    private AccountRepository accountRepo;
    private NotificationService notificationService;
    private AppealService service;

    @BeforeEach
    void setUp() {
        appealRepo = mock(AppealRepository.class);
        cheatRepo = mock(CheatRecordRepository.class);
        accountRepo = mock(AccountRepository.class);
        notificationService = mock(NotificationService.class);
        service = new AppealService(appealRepo, cheatRepo, accountRepo, notificationService, new ObjectMapper());
    }

    @Test
    void submitCapturesEvidenceAndRunsPrescreen() {
        String alertId = "alert_1";
        Appeal saved = Appeal.builder().appealId("any").build();
        when(cheatRepo.findFirstByAlertId(alertId)).thenReturn(
                Optional.of(CheatRecord.builder().alertId(alertId).cheatType("killaura").riskScore(90).build()));
        when(cheatRepo.countByAlertIdAndRevokedFalse(alertId)).thenReturn(1L);
        Account acc = Account.builder().email("p@x.com").reputation(100).build();
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(acc));
        when(appealRepo.save(any(Appeal.class))).thenReturn(saved);

        Appeal a = service.submit("PT42", "误报申诉", "我是无辜的", alertId);

        assertNotNull(a.getEvidenceJson());
        verify(cheatRepo).findFirstByAlertId(alertId);
    }

    @Test
    void highReputationAndRecordAutoApprovesStage() {
        String alertId = "alert_x";
        when(cheatRepo.countByAlertIdAndRevokedFalse(alertId)).thenReturn(1L);
        Account acc = Account.builder().reputation(100).email("p@x.com").build();
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(acc));
        Appeal saved = Appeal.builder().appealId("id").build();
        when(appealRepo.save(any(Appeal.class))).thenReturn(saved);

        Appeal a = service.submit("PT42", "误报申诉", "", alertId);

        // 信誉(100-50=50) + alertId(20) + 未撤销记录(30) = 100 ≥ 85 → 直达终审
        assertEquals(100, a.getPrescreenScore());
        assertEquals("final", a.getReviewStage());
    }

    @Test
    void lowReputationNoRecordEntersLevel1Manual() {
        Account acc = Account.builder().reputation(30).email("p@x.com").build();
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(acc));
        Appeal saved = Appeal.builder().appealId("id").build();
        when(appealRepo.save(any(Appeal.class))).thenReturn(saved);

        Appeal a = service.submit("PT42", "其他", "", null);

        assertEquals("level1", a.getReviewStage());
        assertEquals("support", a.getReviewRole());
        assertNull(a.getEvidenceJson());
    }

    @Test
    void approveRevokesRecordRestoresReputationAndNotifies() {
        String alertId = "alert_r";
        Appeal pending = Appeal.builder()
                .appealId("a1").pteid("PT42").alertId(alertId).status("pending").build();
        when(appealRepo.findById("a1")).thenReturn(Optional.of(pending));
        Mockito.doNothing().when(cheatRepo).revokeByAlert(alertId);
        Account acc = Account.builder().email("p@x.com").status("locked_inspect").reputation(40).build();
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(acc));

        Appeal a = service.review("a1", "approve", "admin", "techlead", "确认误报");

        assertEquals("approved", a.getStatus());
        assertEquals("final", a.getReviewStage());
        verify(cheatRepo).revokeByAlert(alertId);
        assertEquals(100, acc.getReputation());
        assertEquals("normal", acc.getStatus());
        verify(accountRepo).save(acc);
        verify(notificationService).sendToOne(eq("PT42"),
                anyString(), anyString(), anyString(), anyString(),
                anyString(), anySet());
    }

    @Test
    void rejectDoesNotRevoke() {
        Appeal pending = Appeal.builder().appealId("a2").pteid("PT42").alertId("alert_r").status("pending").build();
        when(appealRepo.findById("a2")).thenReturn(Optional.of(pending));
        Account acc = Account.builder().email("p@x.com").build();
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(acc));

        Appeal a = service.review("a2", "reject", "admin", "support", "证据不足");

        assertEquals("rejected", a.getStatus());
        verify(cheatRepo, never()).revokeByAlert(any());
        verify(accountRepo, never()).save(any());
    }

    @Test
    void missingAppealReturnsNull() {
        when(appealRepo.findById("none")).thenReturn(Optional.empty());
        assertNull(service.review("none", "approve", "admin", "techlead", ""));
    }

    @Test
    void advanceMovesToNextStage() {
        Appeal a = Appeal.builder().appealId("a3").pteid("PT42").status("in_review").reviewStage("level1").reviewRole("support").build();
        when(appealRepo.findById("a3")).thenReturn(Optional.of(a));

        service.review("a3", "advance", "admin", "", "");

        assertEquals("level2", a.getReviewStage());
        assertEquals("analyst", a.getReviewRole());
    }
}