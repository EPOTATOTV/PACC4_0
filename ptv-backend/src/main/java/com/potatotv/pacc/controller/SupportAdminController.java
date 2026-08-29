package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.SupportTicket;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.SupportTicketRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.1 客服 / 申诉处理接口（受 X-Admin-Key 保护）：
 * 申诉审批、工单流转（open -> in_progress -> resolved -> closed）、待办统计。
 */
@RestController
@RequestMapping("/api/admin/support")
@RequiredArgsConstructor
public class SupportAdminController {

    private final AppealRepository appealRepository;
    private final SupportTicketRepository ticketRepository;

    /** 待处理申诉列表。 */
    @GetMapping("/appeals")
    public List<Appeal> pendingAppeals(@RequestParam(defaultValue = "pending") String status) {
        return appealRepository.findByStatusOrderByCreatedAtAsc(status);
    }

    /** 审批申诉（approve 撤销对应作弊记录由调用方自行处理，此处更新申诉状态）。 */
    @PostMapping("/appeals/{id}/review")
    public ResponseEntity<?> reviewAppeal(@PathVariable String id, @RequestBody Map<String, String> body) {
        return appealRepository.findById(id)
                .map(a -> {
                    a.setStatus(body.getOrDefault("status", "approved"));
                    a.setReviewer(body.getOrDefault("reviewer", "admin"));
                    a.setReviewComment(body.getOrDefault("comment", ""));
                    a.setReviewedAt(Instant.now());
                    appealRepository.save(a);
                    return ResponseEntity.ok(Map.of("appeal_id", a.getAppealId(), "status", a.getStatus()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 工单列表（按状态）。 */
    @GetMapping("/tickets")
    public List<SupportTicket> tickets(@RequestParam(defaultValue = "open") String status) {
        return ticketRepository.findByStatusOrderByCreatedAtAsc(status);
    }

    /** 工单流转。 */
    @PostMapping("/tickets/{id}/transition")
    public ResponseEntity<?> transition(@PathVariable String id, @RequestBody Map<String, String> body) {
        return ticketRepository.findById(id)
                .map(t -> {
                    t.setStatus(body.getOrDefault("status", "open"));
                    t.setAssignee(body.getOrDefault("assignee", "support"));
                    t.setResolution(body.getOrDefault("resolution", t.getResolution()));
                    t.setUpdatedAt(Instant.now());
                    ticketRepository.save(t);
                    return ResponseEntity.ok(Map.of("ticket_id", t.getTicketId(), "status", t.getStatus()));
                })
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** 客服待办统计（大屏）。 */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pending_appeals", appealRepository.countByStatus("pending"));
        out.put("open_tickets", ticketRepository.countByStatus("open"));
        out.put("in_progress_tickets", ticketRepository.countByStatus("in_progress"));
        return out;
    }
}