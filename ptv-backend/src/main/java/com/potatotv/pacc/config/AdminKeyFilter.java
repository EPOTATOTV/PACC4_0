package com.potatotv.pacc.config;

import com.potatotv.pacc.controller.AdminAuthController;
import com.potatotv.pacc.service.AdminTokenService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 管理后台认证：
 * <ol>
 *   <li>校验 X-Admin-Key 是否为已配置的管理员 API Key（超管 / 运维 Key 恒定时间比较）；</li>
 *   <li>或该校验通过的管理员会话令牌（飞书登录由 {@link AdminTokenService} 签发，携带 role）；</li>
 *   <li>会话令牌额外校验来源指纹（IP+UA），防止令牌被拿到其它设备上冒用；</li>
 *   <li>对管理接口做 Origin 校验，拒绝跨站来源的爬虫/伪造请求。</li>
 * </ol>
 * 登录入口（login / 飞书 OAuth）放行，由处理器内部完成校验与审计。
 */
public class AdminKeyFilter extends OncePerRequestFilter {

    private final String expectedSha256;
    private final AdminTokenService adminTokenService;
    private final Set<String> allowedOrigins = new HashSet<>();

    /** 管理端会话 cookie 名（与 AdminAuthController 下发一致）。 */
    private static final String ADMIN_COOKIE = "pacc_admin";

    public AdminKeyFilter(String expectedKey, AdminTokenService adminTokenService, String allowedOriginsCsv) {
        this.expectedSha256 = sha256(expectedKey == null ? "" : expectedKey);
        this.adminTokenService = adminTokenService;
        if (allowedOriginsCsv != null) {
            Arrays.stream(allowedOriginsCsv.split(","))
                    .map(String::trim).filter(s -> !s.isEmpty())
                    .forEach(allowedOrigins::add);
        }
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 放行登录入口与登录态探测/登出（处理器内自行校验 cookie），其余 /api/admin/** 需认证
        return !uri.startsWith("/api/admin/")
                || uri.equals("/api/admin/login")
                || uri.equals("/api/admin/me")
                || uri.equals("/api/admin/logout")
                || uri.startsWith("/api/admin/feishu/");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        // 1) Origin 校验：管理接口仅接受白名单来源；无 Origin（服务端工具/同源）放行给认证关卡
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank() && !allowedOrigins.contains(origin)) {
            respond(response, HttpServletResponse.SC_FORBIDDEN, "{\"error\":\"拒绝的跨站来源\"}");
            return;
        }

        // 2) 认证：静态 Key 或来源指纹绑定的会话令牌（header 或 HttpOnly cookie，任一有效即可）
        String provided = request.getHeader("X-Admin-Key");
        boolean validKey = provided != null && MessageDigest.isEqual(
                expectedSha256.getBytes(StandardCharsets.UTF_8),
                sha256(provided).getBytes(StandardCharsets.UTF_8));
        // 浏览器管理后台凭 HttpOnly cookie 会话（JS 不可读），桌面工具可走 X-Admin-Key 带同一 JWT
        String session = provided != null ? provided : cookieValue(request, ADMIN_COOKIE);
        String sessionRole = session == null ? null
                : adminTokenService.parseRoleWithFingerprint(session, fingerprint(request));
        boolean validSession = sessionRole != null;
        if (!validKey && !validSession) {
            respond(response, HttpServletResponse.SC_UNAUTHORIZED, "{\"error\":\"管理后台认证失败\"}");
            return;
        }
        // 透出操作人身份与角色，供审计切面/拦截器读取：
        // 会话令牌 subject 即管理员身份；静态 Key 路径以密钥指纹作为操作人
        String actor;
        String role;
        if (validSession) {
            actor = adminTokenService.identityOf(session);
            role = sessionRole;
        } else {
            actor = sha256(provided).substring(0, 8);
            role = "api-key";
        }
        request.setAttribute("adminActor", actor);
        request.setAttribute("adminRole", role);
        chain.doFilter(request, response);
    }

    private static void respond(HttpServletResponse response, int status, String body) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(body);
    }

    /** 来源指纹（与登录签发一致）：客户端真实 IP + UA。 */
    private static String fingerprint(HttpServletRequest request) {
        return AdminAuthController.fingerprint(clientIp(request), request.getHeader("User-Agent"));
    }

    /** 客户端 IP：优先网关透传的 X-Forwarded-For 首段，回退连接地址。 */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}