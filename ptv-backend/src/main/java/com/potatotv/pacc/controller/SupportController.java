package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.FaqEntry;
import com.potatotv.pacc.domain.SupportTicket;
import com.potatotv.pacc.service.SupportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * v4.7 客服工单系统接口（受 /api/admin/** X-Admin-Key 前置过滤保护）：
 * 工单创建/查询/回复(SLA)/解决、FAQ 知识库与智能回复、简单数据分析。
 */
@RestController
@RequestMapping("/api/admin/support")
@RequiredArgsConstructor
@SuppressWarnings("null")
public class SupportController {

    private final SupportService supportService;

    /** 工单列表，可按分类/状态过滤。 */
    @GetMapping("/tickets")
    public List<SupportTicket> tickets(@RequestParam(required = false) String category,
                                       @RequestParam(required = false) String status) {
        // 兼容旧调用方传小写状态（如 open），统一归一化为大写枚举
        return supportService.listTickets(
                upper(category), upper(status));
    }

    private static String upper(String s) {
        if (s == null || s.isBlank()) return null;
        return s.toUpperCase(Locale.ROOT);
    }

    /** 创建工单：pteid/category/title/description。 */
    @PostMapping("/tickets")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title 不能为空"));
        }
        String category = body.get("category");
        if (category == null || category.isBlank()) {
            category = "TECHNICAL";
        }
        SupportTicket t = supportService.createTicket(
                body.get("pteid"), category, title, body.get("description"));
        return ResponseEntity.ok(Map.of(
                "id", t.getId(),
                "status", t.getStatus(),
                "priority", t.getPriority(),
                "created_at", String.valueOf(t.getCreatedAt())));
    }

    /** 回复工单：返回工单 + SLA 首响信息。 */
    @PostMapping("/tickets/{id}/reply")
    public ResponseEntity<?> reply(@PathVariable String id, @RequestBody Map<String, String> body) {
        String reply = body.get("reply");
        if (reply == null || reply.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "reply 不能为空"));
        }
        Map<String, Object> result = supportService.replyTicket(id, reply, body.get("responder"));
        if (result == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(result);
    }

    /** 解决工单。 */
    @PostMapping("/tickets/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable String id, @RequestBody(required = false) Map<String, String> body) {
        String responder = body == null ? null : body.get("responder");
        SupportTicket t = supportService.resolveTicket(id, responder);
        if (t == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of(
                "id", t.getId(),
                "status", t.getStatus(),
                "resolved_at", String.valueOf(t.getResolvedAt())));
    }

    /** 智能回复：body {question}，命中返回官方回答，未命中返回已转人工占位。 */
    @PostMapping("/tickets/{id}/smart")
    public ResponseEntity<?> smart(@PathVariable String id, @RequestBody Map<String, String> body) {
        String question = body.get("question");
        if (question == null || question.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question 不能为空"));
        }
        FaqEntry hit = supportService.smartReply(question);
        String answer = hit == null ? supportService.smartReplyWithFallback(question) : hit.getAnswer();
        return ResponseEntity.ok(Map.of(
                "question", question,
                "answer", answer,
                "hit", hit != null,
                "faq_id", hit == null ? null : hit.getId()));
    }

    /** FAQ 列表。 */
    @GetMapping("/faq")
    public List<FaqEntry> faqList() {
        return supportService.listFaq();
    }

    /** 新增 FAQ。 */
    @PostMapping("/faq")
    public ResponseEntity<?> addFaq(@RequestBody Map<String, String> body) {
        String question = body.get("question");
        String answer = body.get("answer");
        if (question == null || question.isBlank() || answer == null || answer.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question/answer 不能为空"));
        }
        FaqEntry f = supportService.addFaq(question, answer, body.get("keywords"));
        return ResponseEntity.ok(Map.of(
                "id", f.getId(),
                "question", f.getQuestion(),
                "keywords", f.getKeywords()));
    }

    /** 删除 FAQ。 */
    @DeleteMapping("/faq/{id}")
    public ResponseEntity<?> deleteFaq(@PathVariable String id) {
        if (!supportService.faqExists(id)) {
            return ResponseEntity.notFound().build();
        }
        supportService.deleteFaq(id);
        return ResponseEntity.ok(Map.of("deleted", true, "id", id));
    }

    /** 简单数据分析：开放量、分类/优先级分布、平均首响秒、智能命中率。 */
    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return supportService.dashboard();
    }

    /** 智能回复命中率。 */
    @GetMapping("/smart/hit-rate")
    public Map<String, Object> smartHitRate() {
        return Map.of("hit_rate", supportService.smartReplyHitRate());
    }
}
