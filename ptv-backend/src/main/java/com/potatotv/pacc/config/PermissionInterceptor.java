package com.potatotv.pacc.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.AdminRole;
import com.potatotv.pacc.repository.AdminRoleRepository;
import com.potatotv.pacc.security.RequirePermission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 方法级权限拦截器：读取 {@link AdminKeyFilter} 写入的 {@code adminRole} 与角色权限矩阵，
 * 对标注 {@link RequirePermission} 的方法做模块×操作判定。
 * <ul>
 *   <li>super-admin / api-key 全放行；</li>
 *   <li>其余角色按 {@code module:operation} 命中 granted 才放行；</li>
 *   <li>未标注方法一律放行（additive）。</li>
 * </ul>
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(PermissionInterceptor.class);

    private final AdminRoleRepository roleRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionInterceptor(AdminRoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequirePermission requirement = handlerMethod.getMethodAnnotation(RequirePermission.class);
        if (requirement == null) {
            return true;
        }
        String role = (String) request.getAttribute("adminRole");
        // 静态 Key / 超管全放行
        if ("api-key".equals(role) || "super-admin".equals(role)) {
            return true;
        }
        if (role == null || role.isBlank()) {
            respond403(response, "管理后台认证失败");
            return false;
        }
        String required = requirement.value();
        boolean granted = hasPermission(role, required);
        if (!granted) {
            log.warn("权限不足 role={} required={} uri={}", role, required, request.getRequestURI());
            respond403(response, "无操作权限");
            return false;
        }
        return true;
    }

    /** 按角色权限矩阵判定 module:operation。 */
    public boolean hasPermission(String role, String permission) {
        if (permission == null || !permission.contains(":")) {
            return false;
        }
        String[] parts = permission.split(":", 2);
        String module = parts[0];
        String action = parts[1];
        AdminRole r = roleByKey(role);
        if (r == null) {
            return false;
        }
        for (Map<String, Object> perm : fromJson(r.getPermissions())) {
            if (!module.equals(perm.get("module"))) continue;
            Object actions = perm.get("actions");
            if (!(actions instanceof List<?> list)) continue;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                if (action.equals(m.get("key"))) {
                    return Boolean.TRUE.equals(m.get("granted"));
                }
            }
        }
        return false;
    }

    /** 返回某角色的可读权限集合（供 /api/admin/me 透出前端按钮级 gating）。 */
    public Set<String> permissionSet(String role) {
        if (role == null || role.isBlank() || "super-admin".equals(role) || "api-key".equals(role)) {
            return Set.of("*");
        }
        AdminRole r = roleByKey(role);
        if (r == null) return Set.of();
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (Map<String, Object> perm : fromJson(r.getPermissions())) {
            String module = String.valueOf(perm.get("module"));
            Object actions = perm.get("actions");
            if (!(actions instanceof List<?> list)) continue;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                if (Boolean.TRUE.equals(m.get("granted"))) {
                    out.add(module + ":" + m.get("key"));
                }
            }
        }
        return out;
    }

    /** 大小写不敏感地按 roleKey 查找角色（会话角色为小写、播种角色表为大写）。 */
    private AdminRole roleByKey(String role) {
        if (role == null || role.isBlank()) return null;
        for (AdminRole r : roleRepository.findAll()) {
            if (role.equalsIgnoreCase(r.getRoleKey())) {
                return r;
            }
        }
        return null;
    }

    private List<Map<String, Object>> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private static void respond403(HttpServletResponse response, String msg) {
        try {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"" + msg + "\"}");
        } catch (Exception ignored) {
            // 写失败忽略
        }
    }
}