package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.SupportTicket;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.SupportTicketRepository;
import com.potatotv.pacc.service.AppealService;
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
@SuppressWarnings("null") // 存储层泛型 null 分析误报（本地定性安全）
public class SupportAdminController {

    private final AppealRepository appealRepository;
    private final SupportTicketRepository ticketRepository;
    private final AppealService appealService;

    /** 待处理申诉列表（可按状态 + 审核阶段过滤）。 */
    @GetMapping("/appeals")
    public List<Appeal> pendingAppeals(@RequestParam(defaultValue = "pending") String status,
                                       @RequestParam(required = false) String stage) {
        List<Appeal> list = appealRepository.findByStatusOrderByCreatedAtAsc(status);
        if (stage != null && !stage.isBlank()) {
            return list.stream().filter(a -> stage.equals(a.getReviewStage())).toList();
        }
        return list;
    }

    /** 申诉详情（含证据快照，供审核）。 */
    @GetMapping("/appeals/{id}")
    public ResponseEntity<?> appealDetail(@PathVariable String id) {
        return appealRepository.findById(id)
                .map(a -> ResponseEntity.ok((Object) a))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 审批申诉。动作：approve（通过并恢复）/ reject（驳回）/ advance（升级到下一级）。
     * reviewer/role/comment 由调用方提供（角色对应 sys/support/analyst/techlead）。
     */
    @PostMapping("/appeals/{id}/review")
    public ResponseEntity<?> reviewAppeal(@PathVariable String id, @RequestBody Map<String, String> body) {
        String action = body.getOrDefault("action", body.getOrDefault("status", "approved"));
        // 兼容旧调用：status=approved/rejected 映射为 approve/reject
        if ("approved".equals(action)) action = "approve";
        if ("rejected".equals(action)) action = "reject";
        Appeal a = appealService.review(id, action,
                body.getOrDefault("reviewer", "admin"),
                body.getOrDefault("role", ""),
                body.getOrDefault("comment", ""));
        if (a == null) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(Map.of(
                "appeal_id", a.getAppealId(),
                "status", a.getStatus(),
                "review_stage", a.getReviewStage()));
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