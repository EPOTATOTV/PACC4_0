package com.potatotv.pacc.config;

import com.potatotv.pacc.service.AdminAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;

/**
 * v4.8 管理员操作审计拦截器：对管理端敏感写操作自动落库审计。
 * <p>仅记录 POST/PUT/DELETE/PATCH 写操作，读取操作量大且非敏感，不在审计范围；
 * 操作人取 {@link AdminKeyFilter} 注入的 {@code adminActor}/{@code adminRole} 请求属性。</p>
 */
@Component
public class AdminAuditInterceptor implements HandlerInterceptor {

    private static final Set<String> WRITE = Set.of("POST", "PUT", "DELETE", "PATCH");
    /** 登录/登出/会话探测等入口由登录审计单独覆盖，避免重复。 */
    private static final Set<String> SKIP_PREFIX = Set.of(
            "/api/admin/login", "/api/admin/me", "/api/admin/logout", "/api/admin/feishu/");

    private final AdminAuditService auditService;

    public AdminAuditInterceptor(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception ex) {
        String method = request.getMethod();
        if (method == null || !WRITE.contains(method.toUpperCase())) {
            return;
        }
        String path = request.getRequestURI();
        for (String prefix : SKIP_PREFIX) {
            if (path.startsWith(prefix)) return;
        }
        if (!path.startsWith("/api/admin/")) return;

        String actor = (String) request.getAttribute("adminActor");
        String role = (String) request.getAttribute("adminRole");

        String[] seg = path.substring("/api/admin/".length()).split("/");
        String entityType = seg.length > 0 ? seg[0] : "admin";
        String entityId = seg.length > 1 ? seg[seg.length - 1] : null;
        String action = seg.length > 1
                ? entityType + "." + seg[1].replaceAll("[^a-zA-Z0-9_-]", "")
                : entityType + "." + method.toLowerCase();

        auditService.record(actor == null ? "system" : actor, role, action, entityType, entityId,
                summarize(request), clientIp(request), method, path, response.getStatus());
    }

    /** 记录脱敏摘要：仅结构化操作类型/对象 ID，不回读请求体明文。 */
    private static String summarize(HttpServletRequest request) {
        return "method=" + request.getMethod() + " path=" + stripQuery(request.getRequestURI());
    }

    private static String stripQuery(String uri) {
        int q = uri.indexOf('?');
        return q > 0 ? uri.substring(0, q) : uri;
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