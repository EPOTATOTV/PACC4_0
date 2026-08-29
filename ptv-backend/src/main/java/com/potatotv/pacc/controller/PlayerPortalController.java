package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.SupportTicket;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.SupportTicketRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v4.1 玩家自助门户（受玩家 JWT 保护）：
 * 个人记录查询 / 在线申诉 / 客服工单 / 申诉与工单历史。
 */
@RestController
@RequestMapping("/api/player")
@RequiredArgsConstructor
@SuppressWarnings("null") // 流/存储层泛型 null 分析误报（本地定性安全）
public class PlayerPortalController {

    private final AppealRepository appealRepository;
    private final SupportTicketRepository ticketRepository;
    private final CheatRecordRepository recordRepository;

    private String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }

    /** 我的作弊记录（自助查询）。 */
    @GetMapping("/records")
    public List<CheatRecord> myRecords(HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) return List.of();
        return recordRepository.findAll().stream()
                .filter(r -> pteid.equals(r.getPteid()))
                .sorted((a, b) -> b.getOccurredAt().compareTo(a.getOccurredAt()))
                .toList();
    }

    /** 提交在线申诉（红屏/误报）。 */
    @PostMapping("/appeals")
    public ResponseEntity<?> submitAppeal(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        Appeal a = Appeal.builder()
                .appealId(UUID.randomUUID().toString())
                .pteid(pteid)
                .alertId(body.get("alert_id"))
                .reason(body.getOrDefault("reason", "误报申诉"))
                .description(body.getOrDefault("description", ""))
                .status("pending")
                .createdAt(Instant.now())
                .build();
        appealRepository.save(a);
        return ResponseEntity.ok(Map.of("appeal_id", a.getAppealId(), "status", a.getStatus()));
    }

    /** 我的申诉列表。 */
    @GetMapping("/appeals")
    public List<Appeal> myAppeals(HttpServletRequest req) {
        String pteid = pteidOf(req);
        return pteid.isEmpty() ? List.of() : appealRepository.findByPteidOrderByCreatedAtDesc(pteid);
    }

    /** 提交客服工单（多渠道：email/qq/discord/ticket）。 */
    @PostMapping("/tickets")
    public ResponseEntity<?> submitTicket(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        String channel = body.getOrDefault("channel", "ticket");
        if (!List.of("ticket", "email", "qq", "discord", "admin").contains(channel)) {
            return ResponseEntity.badRequest().body(Map.of("error", "未知渠道: " + channel));
        }
        SupportTicket t = SupportTicket.builder()
                .ticketId(UUID.randomUUID().toString())
                .pteid(pteid)
                .channel(channel)
                .subject(body.getOrDefault("subject", "问题反馈"))
                .body(body.getOrDefault("body", ""))
                .status("open")
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        ticketRepository.save(t);
        return ResponseEntity.ok(Map.of("ticket_id", t.getTicketId(), "status", t.getStatus()));
    }

    /** 我的工单列表。 */
    @GetMapping("/tickets")
    public List<SupportTicket> myTickets(HttpServletRequest req) {
        String pteid = pteidOf(req);
        return pteid.isEmpty() ? List.of() : ticketRepository.findByPteidOrderByCreatedAtDesc(pteid);
    }

    /** 工单详情。 */
    @GetMapping("/tickets/{id}")
    public ResponseEntity<?> ticketDetail(@PathVariable String id, HttpServletRequest req) {
        String pteid = pteidOf(req);
        return ticketRepository.findById(id)
                .filter(t -> pteid.equals(t.getPteid()))
                .map(t -> ResponseEntity.ok((Object) t))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 自助诊断摘要：我的风险概览。 */
    @GetMapping("/summary")
    public Map<String, Object> summary(HttpServletRequest req) {
        String pteid = pteidOf(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pteid", pteid);
        List<CheatRecord> records = myRecords(req);
        out.put("record_count", records.size());
        out.put("revoked_count", records.stream().filter(CheatRecord::isRevoked).count());
        out.put("pending_appeals", appealRepository.findByPteidOrderByCreatedAtDesc(pteid)
                .stream().filter(a -> "pending".equals(a.getStatus())).count());
        out.put("open_tickets", ticketRepository.findByPteidOrderByCreatedAtDesc(pteid)
                .stream().filter(t -> !"closed".equals(t.getStatus())).count());
        return out;
    }
}
