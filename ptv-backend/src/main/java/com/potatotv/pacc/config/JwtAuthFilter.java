package com.potatotv.pacc.config;

import com.potatotv.pacc.service.TokenService;
import com.potatotv.pto.PtoToken;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * JWT 认证过滤器：从 Authorization/accessToken 解析玩家 PTEID 并注入请求属性。
 * 用于玩家端 REST 接口（账号信息、设备绑定、结果查询）。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final PtoToken pto;

    public JwtAuthFilter(@Value("${pacc.security.jwt-secret}") String secret) {
        this.pto = new PtoToken(secret.getBytes(StandardCharsets.UTF_8), TokenService.ISSUER);
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String pteid = null;
        String header = request.getHeader("Authorization");
        String bearer = (header != null && header.startsWith("Bearer ")) ? header.substring(7) : null;
        // 浏览器门户：会话令牌位于 HttpOnly cookie；桌面客户端则走 Authorization 头
        if (bearer == null) {
            bearer = cookieValue(request, "pacc_player");
        }
        if (bearer != null && !bearer.isBlank()) {
            try {
                pteid = pto.verify(bearer).subject();
            } catch (Exception ignored) {
                // 未通过认证则视为匿名
            }
        }
        if (pteid != null) {
            request.setAttribute("pteid", pteid);
        }
        // 玩家门户 REST：必须携带有效玩家 JWT，否则拒绝（避免匿名进入触发 NPE/数据异常）
        String uri = request.getRequestURI() == null ? "" : request.getRequestURI();
        if (uri.startsWith("/api/player") && pteid == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"请登录后访问玩家中心\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        return Arrays.stream(cookies).filter(c -> name.equals(c.getName()))
                .map(Cookie::getValue).findFirst().orElse(null);
    }
}