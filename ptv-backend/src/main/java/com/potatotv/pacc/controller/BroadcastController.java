package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.BroadcastService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v5.0 赛事直播转播管理（受 X-Admin-Key / 管理会话保护，super-admin/operator 均可用）。
 * <p>管理端维护多条 OBS 推送的 B 站直播间转播；玩家端由 PlayerP0Controller 只读消费。</p>
 */
@RestController
@RequestMapping("/api/admin/stream-live")
@RequiredArgsConstructor
public class BroadcastController {

    private final BroadcastService broadcastService;

    @GetMapping
    public ResponseEntity<?> list(HttpServletRequest request) {
        return safeCall(() -> {
            var rows = broadcastService.list(role(request));
            return Map.of("rows", rows, "total", rows.size());
        });
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return safeCall(() -> broadcastService.create(role(request), body));
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                    @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return safeCall(() -> broadcastService.update(role(request), id, body));
    }

    @PutMapping("/{id}/live")
    public ResponseEntity<?> setLive(@PathVariable String id,
                                     @RequestBody Map<String, Boolean> body, HttpServletRequest request) {
        boolean live = Boolean.TRUE.equals(body.getOrDefault("live", Boolean.FALSE));
        return safeCall(() -> {
            var b = broadcastService.setLive(role(request), id, live);
            return Map.of("id", b.getId(), "live", b.isLive());
        });
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id, HttpServletRequest request) {
        return safeCall(() -> {
            broadcastService.delete(role(request), id);
            return Map.of("deleted", id);
        });
    }

    private static String role(HttpServletRequest request) {
        return (String) request.getAttribute("adminRole");
    }

    private static ResponseEntity<?> safeCall(Supplier<?> s) {
        try {
            return ResponseEntity.ok(s.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", "操作失败，请稍后再试"));
        }
    }

    @FunctionalInterface
    private interface Supplier<T> {
        T get();
    }
}