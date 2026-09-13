package com.potatotv.pacc.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.potatotv.pacc.domain.FaqEntry;
import com.potatotv.pacc.domain.SupportTicket;
import com.potatotv.pacc.repository.FaqEntryRepository;
import com.potatotv.pacc.repository.SupportTicketRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

/**
 * v4.7 客服工单服务确定性单测：默认状态/优先级、回复置 RESPONDED 与 SLA 计算、智能回复关键词命中/回退。
 */
class SupportServiceTest {

    private SupportTicketRepository ticketRepo;
    private FaqEntryRepository faqRepo;
    private SupportService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ticketRepo = mock(SupportTicketRepository.class);
        faqRepo = mock(FaqEntryRepository.class);
        service = new SupportService(ticketRepo, faqRepo, mock(NotificationService.class));
    }

    @Test
    void createTicketDefaultsToOpenAndP3() {
        Answer<SupportTicket> passThrough = inv -> inv.getArgument(0);
        when(ticketRepo.save(any(SupportTicket.class))).thenAnswer(passThrough);

        SupportTicket t = service.createTicket("PT42", "TECHNICAL", "连不上对局", "提示网络异常");

        assertEquals("OPEN", t.getStatus());
        assertEquals("P3", t.getPriority());
        assertNotNull(t.getId());
        assertNotNull(t.getCreatedAt());
        assertEquals("TECHNICAL", t.getCategory());
    }

    @Test
    void replySetsRespondedAndComputesInSlaForP0() {
        Instant created = Instant.now().minusSeconds(5 * 60); // 5 分钟前
        SupportTicket t = SupportTicket.builder()
                .id("t1").category("TECHNICAL").title("x").status("OPEN")
                .priority("P0").createdAt(created).build();
        when(ticketRepo.findById("t1")).thenReturn(Optional.of(t));
        when(ticketRepo.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.replyTicket("t1", "已处理", "小王");

        @SuppressWarnings("unchecked")
        SupportTicket saved = (SupportTicket) result.get("ticket");
        @SuppressWarnings("unchecked")
        Map<String, Object> slaInfo = (Map<String, Object>) result.get("sla_info");

        assertEquals("RESPONDED", saved.getStatus());
        assertNotNull(saved.getFirstReplyAt());
        // P0 预算 7200s，5 分钟(300s) 内首响，未超时
        assertEquals(7200L, slaInfo.get("sla_budget_seconds"));
        assertTrue((Boolean) slaInfo.get("in_sla"));

        // 首响秒数约 300s（允许秒级偏移）
        long secs = (Long) slaInfo.get("first_reply_seconds");
        assertTrue(secs >= 290 && secs <= 310, "首响秒应在 300s 附近，实际=" + secs);
    }

    @Test
    void replyRequestsLateP0TicketFailsSla() {
        Instant created = Instant.now().minusSeconds(3 * 3600); // 3 小时前
        SupportTicket t = SupportTicket.builder()
                .id("t2").status("OPEN").priority("P0").createdAt(created).build();
        when(ticketRepo.findById("t2")).thenReturn(Optional.of(t));
        when(ticketRepo.save(any(SupportTicket.class))).thenAnswer(inv -> inv.getArgument(0));

        Map<String, Object> result = service.replyTicket("t2", "来晚了", "admin");

        @SuppressWarnings("unchecked")
        Map<String, Object> slaInfo = (Map<String, Object>) result.get("sla_info");
        // 3h=10800s > P0 预算 7200s → 超时
        assertFalse((Boolean) slaInfo.get("in_sla"));
        assertTrue((Long) slaInfo.get("first_reply_seconds") > 7200L);
    }

    @Test
    void smartReplyHitsByKeywordAndFallsBackOnMiss() {
        FaqEntry faq = FaqEntry.builder()
                .id("f1").question("如何重置密码").answer("请在登录页点击找回密码")
                .keywords("重置密码,找回,忘记密码").build();
        when(faqRepo.findAll()).thenReturn(List.of(faq));

        // 提问含关键词 → 命中返回官方回答
        assertNotNull(service.smartReply("请问重置密码怎么操作"));
        assertEquals("请在登录页点击找回密码", service.smartReplyWithFallback("请问重置密码怎么操作"));

        // 提问不含任何关键词 → 未命中返回 null / 转人工占位
        assertNull(service.smartReply("今天天气不错"));
        assertTrue(service.smartReplyWithFallback("今天天气不错").contains("人工"));

        // 命中率：2 次尝试 1 次命中
        assertEquals(0.5, service.smartReplyHitRate());
    }
}