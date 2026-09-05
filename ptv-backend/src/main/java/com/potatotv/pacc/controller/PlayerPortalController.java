package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.SupportTicket;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.SupportTicketRepository;
import com.potatotv.pacc.service.AppealService;
import com.potatotv.pacc.service.CounterMeasureRiskService;
import com.potatotv.pacc.service.IntegrityGuardService.Input;
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
    private final AppealService appealService;
    private final CounterMeasureRiskService counterMeasureRiskService;

    private String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }

    /** 会话探测：供前端判断是否已登录并取回 PTEID（数据由 cookie 会话解析）。 */
    @GetMapping("/me")
    public Map<String, Object> me(HttpServletRequest req) {
        String pteid = pteidOf(req);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pteid", pteid);
        return out;
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

    /** 客户端完整性自检上报（受玩家 JWT 保护）：完整性破坏/可疑按对抗等级落库决策。 */
    @PostMapping("/countermeasure/integrity")
    public ResponseEntity<?> reportIntegrity(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        boolean sigValid = bool(body.get("signature_valid"));
        boolean dseOp = bool(body.get("dse_operating"));
        boolean tsOn = bool(body.get("testsigning_on"));
        boolean codeMatch = bool(body.get("code_hash_match"));
        List<String> hooks = new java.util.ArrayList<>();
        Object h = body.get("hook_found");
        if (h instanceof List) for (Object x : (List<?>) h) if (x != null) hooks.add(x.toString());

        CounterMeasureRiskService.CounterMeasureResult r = counterMeasureRiskService.handle(
                pteid, "", null, null, new Input(sigValid, dseOp, tsOn, codeMatch, hooks));
        return ResponseEntity.ok(Map.of(
                "confidence_tier", r.tier().name(),
                "risk_score", r.riskScore(),
                "forced_redscreen", r.forcedRedscreen()));
    }

    private static boolean bool(Object o) {
        return o != null && Boolean.parseBoolean(o.toString());
    }

    /** 提交在线申诉（红屏/误报）。经 AppealService 做证据快照 + 自动初筛。 */
    @PostMapping("/appeals")
    public ResponseEntity<?> submitAppeal(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        Appeal a = appealService.submit(pteid,
                body.getOrDefault("reason", "误报申诉"),
                body.getOrDefault("description", ""),
                body.get("alert_id"));
        return ResponseEntity.ok(Map.of(
                "appeal_id", a.getAppealId(),
                "status", a.getStatus(),
                "review_stage", a.getReviewStage(),
                "prescreen_score", a.getPrescreenScore()));
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
