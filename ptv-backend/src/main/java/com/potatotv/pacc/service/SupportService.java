package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.FaqEntry;
import com.potatotv.pacc.domain.SupportTicket;
import com.potatotv.pacc.repository.FaqEntryRepository;
import com.potatotv.pacc.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * v4.7 客服工单服务：工单创建/查询/回复(SLA)/解决、FAQ 知识库与关键词智能回复、简单数据分析。
 */
@Service
@RequiredArgsConstructor
public class SupportService {

    /** 各优先级首响 SLA 预算（秒）：P0=2h, P1=8h, P2=24h, P3=48h。 */
    private static final Map<String, Long> SLA_BUDGET = Map.of(
            "P0", 7200L, "P1", 28800L, "P2", 86400L, "P3", 172800L);

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s,，。.!?！？;；:：]+");

    private final SupportTicketRepository ticketRepository;
    private final FaqEntryRepository faqRepository;

    /** 智能回复命中统计（进程内近似，用于 hit_rate 展示）。 */
    private final AtomicLong smartHits = new AtomicLong();
    private final AtomicLong smartAttempts = new AtomicLong();

    @Transactional
    public SupportTicket createTicket(String pteid, String category, String title, String description) {
        SupportTicket t = SupportTicket.builder()
                .id(UUID.randomUUID().toString())
                .pteid(pteid)
                .category(category)
                .title(title)
                .description(description == null ? "" : description)
                .status("OPEN")
                .priority("P3")
                .createdAt(Instant.now())
                .build();
        return ticketRepository.save(t);
    }

    public List<SupportTicket> listTickets(String category, String status) {
        boolean hasCat = category != null && !category.isBlank();
        boolean hasStatus = status != null && !status.isBlank();
        if (hasCat && hasStatus) {
            return ticketRepository.findByCategoryAndStatusOrderByCreatedAtDesc(category, status);
        }
        if (hasCat) {
            return ticketRepository.findByCategoryOrderByCreatedAtDesc(category);
        }
        if (hasStatus) {
            return ticketRepository.findByStatusOrderByCreatedAtDesc(status);
        }
        return ticketRepository.findAllByOrderByCreatedAtDesc();
    }

    /**
     * 回复工单：state 置 RESPONDED，记录首响时间，返回 {ticket, sla_info}；
     * 工单不存在时返回 null（由 controller 转 404）。
     */
    @Transactional
    public Map<String, Object> replyTicket(String id, String reply, String responder) {
        SupportTicket t = ticketRepository.findById(id).orElse(null);
        if (t == null) {
            return null;
        }
        if (t.getFirstReplyAt() == null) {
            t.setFirstReplyAt(Instant.now());
            t.setAssignee(responder == null || responder.isBlank() ? "support" : responder);
        }
        t.setStatus("RESPONDED");
        t.setUpdatedAt(Instant.now());
        ticketRepository.save(t);

        long firstReplySeconds = t.getCreatedAt() == null ? 0L
                : Duration.between(t.getCreatedAt(), t.getFirstReplyAt()).getSeconds();
        long slaBudget = slaBudgetSeconds(t.getPriority());
        boolean inSla = firstReplySeconds <= slaBudget;

        Map<String, Object> slaInfo = new LinkedHashMap<>();
        slaInfo.put("priority", t.getPriority());
        slaInfo.put("first_reply_seconds", firstReplySeconds);
        slaInfo.put("sla_budget_seconds", slaBudget);
        slaInfo.put("in_sla", inSla);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ticket", t);
        out.put("sla_info", slaInfo);
        return out;
    }

    @Transactional
    public SupportTicket resolveTicket(String id, String responder) {
        SupportTicket t = ticketRepository.findById(id).orElse(null);
        if (t == null) {
            return null;
        }
        t.setStatus("RESOLVED");
        t.setResolvedAt(Instant.now());
        t.setUpdatedAt(Instant.now());
        return ticketRepository.save(t);
    }

    /** 智能回复：按关键词匹配 FAQ，命中返回条目，未命中返回 null。 */
    public FaqEntry smartReply(String question) {
        smartAttempts.incrementAndGet();
        if (question == null || question.isBlank()) {
            return null;
        }
        String q = question.toLowerCase();
        for (FaqEntry f : faqRepository.findAll()) {
            if (matches(f, q)) {
                smartHits.incrementAndGet();
                return f;
            }
        }
        return null;
    }

    /** 智能回复：命中返回官方回答，未命中返回「已转人工」占位。 */
    public String smartReplyWithFallback(String question) {
        FaqEntry f = smartReply(question);
        if (f != null) {
            return f.getAnswer();
        }
        return "已转人工：您的提问暂未命中知识库，客服将尽快跟进。";
    }

    /** 智能回复命中率（进程内近似统计）。 */
    public double smartReplyHitRate() {
        long attempts = smartAttempts.get();
        if (attempts == 0) {
            return 0.0;
        }
        return (double) smartHits.get() / attempts;
    }

    /** 数据分析：开放量、分类/优先级分布、平均首响秒、智能命中率。 */
    public Map<String, Object> dashboard() {
        List<SupportTicket> all = ticketRepository.findAll();

        long openCount = all.stream().filter(t -> "OPEN".equals(t.getStatus())).count();

        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (String c : new String[]{"APPEAL", "TECHNICAL", "ACCOUNT", "FEATURE", "BUSINESS", "REPORT"}) {
            byCategory.put(c, 0L);
        }
        Map<String, Long> byPriority = new LinkedHashMap<>();
        for (String p : new String[]{"P0", "P1", "P2", "P3"}) {
            byPriority.put(p, 0L);
        }
        for (SupportTicket t : all) {
            byCategory.computeIfPresent(t.getCategory(), (k, v) -> v + 1);
            byPriority.computeIfPresent(t.getPriority(), (k, v) -> v + 1);
        }

        OptionalDouble avg = all.stream()
                .filter(t -> t.getFirstReplyAt() != null && t.getCreatedAt() != null)
                .mapToLong(t -> Duration.between(t.getCreatedAt(), t.getFirstReplyAt()).getSeconds())
                .average();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("open_count", openCount);
        out.put("by_category", byCategory);
        out.put("by_priority", byPriority);
        out.put("avg_first_reply_seconds", avg.isEmpty() ? 0.0 : avg.getAsDouble());
        out.put("smart_hit_rate", smartReplyHitRate());
        return out;
    }

    @Transactional
    public FaqEntry addFaq(String question, String answer, String keywords) {
        FaqEntry f = FaqEntry.builder()
                .id(UUID.randomUUID().toString())
                .question(question)
                .answer(answer)
                .keywords(keywords == null ? "" : keywords)
                .createdAt(Instant.now())
                .build();
        return faqRepository.save(f);
    }

    @Transactional
    public void deleteFaq(String id) {
        faqRepository.deleteById(id);
    }

    public boolean faqExists(String id) {
        return faqRepository.existsById(id);
    }

    public List<FaqEntry> listFaq() {
        return faqRepository.findAllByOrderByCreatedAtDesc();
    }

    /** 关键词是否命中：FAQ.keywords 中含于提问，或提问分词命中任一关键词。 */
    boolean matches(FaqEntry f, String q) {
        String kws = f.getKeywords();
        if (kws == null || kws.isBlank()) {
            return f.getQuestion() != null && !f.getQuestion().isBlank()
                    && q.contains(f.getQuestion().toLowerCase());
        }
        String[] arr = kws.split(",");
        for (String raw : arr) {
            String kw = raw.trim().toLowerCase();
            if (!kw.isEmpty() && q.contains(kw)) {
                return true;
            }
        }
        for (String token : WORD_SPLIT.split(q)) {
            if (token.isEmpty()) {
                continue;
            }
            for (String raw : arr) {
                if (token.equals(raw.trim().toLowerCase())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** SLA 预算（秒）：P0=7200, P1=28800, P2=86400, 其余 P3=172800。 */
    long slaBudgetSeconds(String priority) {
        return SLA_BUDGET.getOrDefault(priority == null ? "P3" : priority, 172800L);
    }
}