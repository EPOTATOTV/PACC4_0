package com.potatotv.pacc.config;

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
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                Claims claims = Jwts.parser().verifyWith(key).build()
                        .parseSignedClaims(header.substring(7)).getPayload();
                request.setAttribute("pteid", claims.getSubject());
            } catch (Exception ignored) {
                // 未通过认证则视为匿名
            }
        }
        chain.doFilter(request, response);
    }
}