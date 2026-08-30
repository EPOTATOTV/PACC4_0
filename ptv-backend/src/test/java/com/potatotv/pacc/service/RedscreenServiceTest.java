package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * 红屏决策确定性单测：阈值边界、等级判定、脱敏、冷却与低分不触发。
 */
class RedscreenServiceTest {

    private RedscreenAlertRepository alertRepo;
    private AccountRepository accountRepo;
    private CheatRecordRepository cheatRepo;
    private OnlineStatusService online;
    private InspectService inspect;
    private WebhookDispatcher webhook;
    private RedscreenService service;

    private static final int THRESHOLD = 85;
    private static final int SEVERE = 95;

    @BeforeEach
    void setUp() {
        alertRepo = mock(RedscreenAlertRepository.class);
        accountRepo = mock(AccountRepository.class);
        cheatRepo = mock(CheatRecordRepository.class);
        online = mock(OnlineStatusService.class);
        inspect = mock(InspectService.class);
        webhook = mock(WebhookDispatcher.class);
        service = new RedscreenService(alertRepo, accountRepo, cheatRepo, online, inspect, webhook,
                new ObjectMapper(), THRESHOLD, SEVERE, 10);
    }

    @Test
    void levelBoundaries() {
        assertEquals(0, RedscreenService.levelFor(50, THRESHOLD, SEVERE));
        assertEquals(0, RedscreenService.levelFor(84, THRESHOLD, SEVERE));
        assertEquals(2, RedscreenService.levelFor(85, THRESHOLD, SEVERE));
        assertEquals(2, RedscreenService.levelFor(94, THRESHOLD, SEVERE));
        assertEquals(3, RedscreenService.levelFor(95, THRESHOLD, SEVERE));
        assertEquals(3, RedscreenService.levelFor(100, THRESHOLD, SEVERE));
    }

    @Test
    void belowThresholdDoesNotRedScreen() {
        assertNull(service.decideAndHandle("PT42", "killaura", 50, "bedrock", "低风险"));
    }

    @Test
    void reachingThresholdRaisesLevel2() {
        prepFullFlow();
        RedscreenAlert alert = service.decideAndHandle("PT42", "killaura", 90, "bedrock", "高行为分");
        assertNotNull(alert);
        assertEquals(2, alert.getLevel());
        assertEquals("PT***42", alert.getPteidMasked());
        assertEquals("killaura", alert.getCheatType());
    }

    @Test
    void severeScoreRaisesLevel3() {
        prepFullFlow();
        RedscreenAlert alert = service.decideAndHandle("PT42", "memory_tamper", 97, "java", "强证据");
        assertNotNull(alert);
        assertEquals(3, alert.getLevel());
    }

    @Test
    void cooldownSuppressesDuplicate() {
        when(alertRepo.existsByPteidAndCheatTypeAndOccurredAtAfter(eq("PT42"), eq("killaura"), any()))
                .thenReturn(true);
        assertNull(service.decideAndHandle("PT42", "killaura", 90, "bedrock", "冷却期"));
    }

    @Test
    void maskDesensitizesPteid() {
        assertEquals("PT***56", RedscreenService.mask("PT123456"));
        assertEquals("PT***42", RedscreenService.mask("PT42"));
        assertEquals("****", RedscreenService.mask(null));
        assertEquals("****", RedscreenService.mask("abc"));
    }

    private void prepFullFlow() {
        when(alertRepo.existsByPteidAndCheatTypeAndOccurredAtAfter(anyString(), anyString(), any()))
                .thenReturn(false);
        Account account = Account.builder().reputation(40).pteid("PT42").build();
        when(accountRepo.findById("PT42")).thenReturn(Optional.of(account));
        when(online.onlineCount()).thenReturn(5);
        when(online.broadcast(anyString())).thenReturn(3L);
        when(inspect.enqueue(anyString(), anyString(), Mockito.anyInt())).thenReturn(1);
        when(cheatRepo.findTopByOrderByRecordHashDesc()).thenReturn(Optional.empty());
        Mockito.doNothing().when(webhook).onRedscreen(Mockito.<RedscreenAlert>any(), Mockito.anyInt());
    }
}