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
 * 全局限流过滤器（P1）：对 /api/** 与 /v1/update/** 按“身份 + 请求类别”施加令牌桶限速。
 * <p>必须置于认证过滤器之后，才能拿到 AdminKeyFilter 写入的 adminActor / JwtAuthFilter 写入的 PTEID；
 * 因此在本安全链中最后注册。返回 429 不泄露具体限速策略。</p>
 */
public class RateLimitFilter extends OncePerRequestFilter {

    /** 下载计数上报：匿名接口，除通用令牌桶外还要按 IP 计每日配额。 */
    private static final String DL_TRACK_PATH = "/api/dl/track";

    /**
     * 端侧更新接口（设计文档 §4.11）：无登录态、登录之前就会调用，所以拿不到 PTEID 身份，
     * 只能按 IP 限流。这两个接口不做限流的话，任何人都能无限刷 /v1/update/report 往库里插行。
     */
    private static final String UPDATE_PATH_PREFIX = "/v1/update/";

    private final RateLimiterService rateLimiterService;

    public RateLimitFilter(RateLimiterService rateLimiterService) {
        this.rateLimiterService = rateLimiterService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (uri == null || !limited(uri)) {
            chain.doFilter(request, response);
            return;
        }
        // 下载计数：先按 IP 卡每日配额（防长时间低频刷量），再走通用令牌桶
        if (DL_TRACK_PATH.equals(uri) && !rateLimiterService.allowDailyTrack(clientIp(request))) {
            reject(response);
            return;
        }
        String who = identity(request);
        String method = request.getMethod();
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        boolean sensitive = isSensitive(uri);
        if (!rateLimiterService.allow(who, write, sensitive)) {
            reject(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"请求过于频繁，请稍后再试\"}");
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

    /** 纳入限流的路径：管理端 / 玩家端 REST，以及无登录态的端侧更新接口。 */
    private static boolean limited(String uri) {
        return uri.startsWith("/api/") || uri.startsWith(UPDATE_PATH_PREFIX);
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