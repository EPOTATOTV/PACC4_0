package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Notification;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.NotificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 管理端通知发布：广播公告 / 定向通知。写入 t_notification 并实时 WS 推送。
 */
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
public class NotificationAdminController {

    private final NotificationService notificationService;

    /**
     * 发布通知：{@code title} 必填；{@code pteid} 为空则广播公告。
     * 标注 {@code system:create} 权限：operator 角色仅读，super-admin / api-key 可发。
     */
    @PostMapping("/send")
    @RequirePermission("system:create")
    public ResponseEntity<?> send(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String title = body.get("title");
        if (title == null || title.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title 不能为空"));
        }
        Notification saved;
        String pteid = body.get("pteid");
        if (pteid == null || pteid.isBlank()) {
            saved = notificationService.sendToAll(title, body.get("content"),
                    body.get("scope"), body.get("type"), body.get("priority"));
        } else {
            saved = notificationService.sendToOne(pteid, title, body.get("content"),
                    body.get("scope"), body.get("type"), body.get("priority"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("id", saved.getId());
        out.put("broadcast", saved.getPteid() == null || saved.getPteid().isBlank());
        out.put("createdAt", saved.getCreatedAt() == null ? "" : saved.getCreatedAt().toString());
        return ResponseEntity.ok(out);
    }
}