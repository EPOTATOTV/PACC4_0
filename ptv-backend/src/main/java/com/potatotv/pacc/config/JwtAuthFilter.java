package com.potatotv.pacc.config;

import com.potatotv.pacc.service.TokenService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * JWT 认证过滤器：从 Authorization/accessToken 解析玩家 PTEID 并注入请求属性。
 * 用于玩家端 REST 接口（账号信息、设备绑定、结果查询）。
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final SecretKey key;

    public JwtAuthFilter(@Value("${pacc.security.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String pteid = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = Jwts.parser().requireIssuer(TokenService.ISSUER).verifyWith(key).build()
                        .parseSignedClaims(header.substring(7)).getPayload();
                pteid = claims.getSubject();
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
}