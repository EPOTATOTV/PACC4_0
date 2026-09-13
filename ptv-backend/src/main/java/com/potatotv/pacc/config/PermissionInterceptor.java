package com.potatotv.pacc.config;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.RbacService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

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

    private final RbacService rbacService;

    public PermissionInterceptor(RbacService rbacService) {
        this.rbacService = rbacService;
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
        String adminActor = request.getAttribute("adminActor") == null ? "" : request.getAttribute("adminActor").toString();
        String required = requirement.value();
        boolean granted = rbacService.hasPermission(adminActor, role, required);
        if (!granted) {
            log.warn("权限不足 role={} required={} uri={}", role, required, request.getRequestURI());
            respond403(response, "无操作权限");
            return false;
        }
        return true;
    }

    /** 返回某角色的可读权限集合（供 /api/admin/me 透出前端按钮级 gating）。 */
    public Set<String> permissionSet(String adminId, String role) {
        return rbacService.permissionSet(adminId, role);
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