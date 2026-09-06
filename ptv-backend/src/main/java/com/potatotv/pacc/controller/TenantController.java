package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.TenantService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v4.8 多租户管理接口（受 X-Admin-Key / 管理会话保护）。
 * <p>平台级管理员管理租户与租户管理员；租户级管理员仅能查看其绑定的租户。</p>
 */
@RestController
@RequestMapping("/api/admin/tenant")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenantService;

    @GetMapping("/list")
    public ResponseEntity<?> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String search,
            HttpServletRequest request) {
        return safeCall(() -> tenantService.list(role(request), actor(request), page, size, search));
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<?> get(@PathVariable String tenantId, HttpServletRequest request) {
        return safeCall(() -> tenantService.get(role(request), actor(request), tenantId));
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        return safeCall(() -> tenantService.create(role(request), actor(request), body));
    }

    @PutMapping("/{tenantId}")
    public ResponseEntity<?> update(@PathVariable String tenantId,
                                    @RequestBody Map<String, Object> body, HttpServletRequest request) {
        return safeCall(() -> tenantService.update(role(request), actor(request), tenantId, body));
    }

    @DeleteMapping("/{tenantId}")
    public ResponseEntity<?> delete(@PathVariable String tenantId, HttpServletRequest request) {
        return safeCall(() -> {
            tenantService.delete(role(request), actor(request), tenantId);
            return Map.of("deleted", tenantId);
        });
    }

    @GetMapping("/{tenantId}/admins")
    public ResponseEntity<?> admins(@PathVariable String tenantId, HttpServletRequest request) {
        return safeCall(() -> tenantService.admins(role(request), actor(request), tenantId));
    }

    @PostMapping("/{tenantId}/admins")
    public ResponseEntity<?> addAdmin(@PathVariable String tenantId,
                                      @RequestBody Map<String, String> body, HttpServletRequest request) {
        return safeCall(() -> tenantService.addAdmin(role(request), actor(request), tenantId,
                body.get("identity"), body.get("role")));
    }

    @DeleteMapping("/{tenantId}/admins/{identity}")
    public ResponseEntity<?> removeAdmin(@PathVariable String tenantId, @PathVariable String identity,
                                         HttpServletRequest request) {
        return safeCall(() -> {
            tenantService.removeAdmin(role(request), actor(request), tenantId, identity);
            return Map.of("removed", identity);
        });
    }

    @PutMapping("/{tenantId}/admins/{identity}/enabled")
    public ResponseEntity<?> setAdminEnabled(@PathVariable String tenantId, @PathVariable String identity,
                                             @RequestBody Map<String, Boolean> body, HttpServletRequest request) {
        boolean enabled = Boolean.TRUE.equals(body.getOrDefault("enabled", Boolean.TRUE));
        return safeCall(() -> {
            tenantService.setAdminEnabled(role(request), actor(request), tenantId, identity, enabled);
            return Map.of("identity", identity, "enabled", enabled);
        });
    }

    private static String role(HttpServletRequest request) {
        return (String) request.getAttribute("adminRole");
    }

    private static String actor(HttpServletRequest request) {
        return (String) request.getAttribute("adminActor");
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