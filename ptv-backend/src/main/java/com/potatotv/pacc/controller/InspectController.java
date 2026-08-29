package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.InspectSession;
import com.potatotv.pacc.service.InspectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 远程查端控制台接口：队列、开始、结论（确认 / 误报）、审计。
 */
@RestController
@RequestMapping("/api/admin/inspects")
@RequiredArgsConstructor
public class InspectController {

    private final InspectService inspectService;

    @GetMapping("/pending")
    public List<InspectSession> pending() {
        return inspectService.pending();
    }

    @GetMapping("/all")
    public List<InspectSession> all() {
        return inspectService.all();
    }

    @PostMapping("/{sessionId}/start")
    public ResponseEntity<?> start(@PathVariable String sessionId, @RequestBody(required = false) Map<String, String> body) {
        try {
            return ResponseEntity.ok(inspectService.start(sessionId, body == null ? "operator" : body.getOrDefault("operator", "operator")));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{sessionId}/conclude")
    public ResponseEntity<?> conclude(@PathVariable String sessionId, @RequestBody Map<String, String> body) {
        try {
            // 仅返回脱敏视图，绝不外泄密码哈希 / 设备指纹
            return ResponseEntity.ok(AccountController.AccountView.from(inspectService.conclude(sessionId,
                    body.get("conclusion"), body.get("note"))));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }
}