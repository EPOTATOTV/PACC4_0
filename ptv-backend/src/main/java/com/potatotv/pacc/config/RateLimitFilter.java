package com.potatotv.pacc.config;

import com.potatotv.pacc.service.RateLimiterService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 全局限流过滤器（P1）：对 /api/** 按“身份 + 请求类别”施加令牌桶限速。
 * <p>必须置于认证过滤器之后，才能拿到 AdminKeyFilter 写入的 adminActor / JwtAuthFilter 写入的 PTEID；
 * 因此在本安全链中最后注册。返回 429 不泄露具体限速策略。</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimiterService rateLimiterService;

    public RateLimitFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || !uri.startsWith("/api/")) {
            chain.doFilter(request, response);
            return;
        }
        String who = identity(request);
        String method = request.getMethod();
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        boolean sensitive = isSensitive(uri);
        if (!rateLimiterService.allow(who, write, sensitive)) {
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    /** 请求身份：优先管理端操作人 / 玩家 PTEID，回退客户端 IP。 */
    private static String identity(HttpServletRequest request) {
        Object actor = request.getAttribute("adminActor");
        if (actor != null && !actor.toString().isBlank()) {
            return "admin:" + actor;
        }
        Object pteid = request.getAttribute("pteid");
        if (pteid != null && !pteid.toString().isBlank()) {
            return "player:" + pteid;
        }
        return "ip:" + clientIp(request);
    }

    /** 敏感操作（账号安全 / 凭证类）：更低的突发与速率。 */
    private static boolean isSensitive(String uri) {
        return uri.contains("password") || uri.contains("totp") || uri.contains("recovery")
                || uri.contains("2fa") || uri.contains("trust-device") || uri.contains("revoke");
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