package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.AdminUserRole;
import com.potatotv.pacc.repository.AdminPermissionRepository;
import com.potatotv.pacc.repository.AdminUserRoleRepository;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.AdminAuditService;
import com.potatotv.pacc.service.RbacService;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v5.0 RBAC 管理端点：权限键目录、管理员角色分配/撤销。
 * <p>权限目录由 {@link RbacService} 播种；角色分配写入 t_admin_user_role 支持多角色，并记录审计。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class RbacAdminController {

    private final AdminPermissionRepository permissionRepository;
    private final AdminUserRoleRepository userRoleRepository;
    private final RbacService rbacService;
    private final AdminAuditService auditService;

    /** 权限键目录（只读透出，供前端做矩阵/分配控件）。 */
    @GetMapping("/permissions")
    public ResponseEntity<?> permissions() {
        List<Map<String, String>> rows = permissionRepository.findAllByOrderByModuleAscPermissionKeyAsc().stream()
                .map(p -> {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("key", p.getPermissionKey());
                    m.put("module", p.getModule());
                    m.put("name", p.getName());
                    m.put("description", p.getDescription() == null ? "" : p.getDescription());
                    return m;
                }).toList();
        return ResponseEntity.ok(Map.of("permissions", rows,
                "modules", java.util.List.of(RbacService.MODULES)));
    }

    /** 查看管理员已绑定的角色列表。 */
    @GetMapping("/admins/{adminId}/roles")
    public ResponseEntity<?> adminRoles(@PathVariable String adminId) {
        List<String> roles = userRoleRepository.findByAdminId(adminId).stream()
                .map(AdminUserRole::getRoleId).toList();
        return ResponseEntity.ok(Map.of("admin_id", adminId, "roles", roles));
    }

    /**
     * 分配角色：body 传 {@code {"roles":["OPERATOR",...]}}，覆盖式写入。
     * 管理员可挂多个角色，权限取并集。
     */
    @PostMapping("/admins/{adminId}/roles")
    @RequirePermission("roles:update")
    public ResponseEntity<?> assignRoles(@PathVariable String adminId,
                                         @RequestBody Map<String, Object> body,
                                         HttpServletRequest request) {
        if (adminId == null || adminId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "管理员标识不能为空"));
        }
        List<?> raw = body.get("roles") instanceof List<?> l ? l : List.of();
        List<String> roles = new ArrayList<>();
        for (Object o : raw) {
            String r = String.valueOf(o);
            if (!r.isBlank() && !roles.contains(r)) roles.add(r);
        }
        userRoleRepository.deleteByAdminId(adminId);
        List<AdminUserRole> rows = new ArrayList<>();
        for (String roleId : roles) {
            rows.add(AdminUserRole.builder()
                    .id("ur-" + UUID.randomUUID().toString())
                    .adminId(adminId)
                    .roleId(roleId)
                    .createdAt(Instant.now())
                    .build());
        }
        userRoleRepository.saveAll(rows);
        String operator = request.getAttribute("adminActor") == null ? "api-key" : request.getAttribute("adminActor").toString();
        String opRole = request.getAttribute("adminRole") == null ? "" : request.getAttribute("adminRole").toString();
        auditService.record(operator, opRole, "rbac.assignRoles", "admin", adminId,
                "角色集=" + roles, clientIp(request), "POST", "/api/admin/admins/" + adminId + "/roles", 200);
        return ResponseEntity.ok(Map.of("admin_id", adminId, "roles", roles));
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}