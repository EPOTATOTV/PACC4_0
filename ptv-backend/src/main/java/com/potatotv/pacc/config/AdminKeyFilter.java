package com.potatotv.pacc.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;

/**
 * 管理后台 API Key 认证：管理接口需携带 X-Admin-Key。
 * 使用恒定时间比较防止时序侧信道。
 */
public class AdminKeyFilter extends OncePerRequestFilter {

    private final String expectedSha256;

    public AdminKeyFilter(String expectedKey) {
        this.expectedSha256 = sha256(expectedKey == null ? "" : expectedKey);
    }

    @Override
    protected boolean shouldNotFilter(@NonNull HttpServletRequest request) {
        String uri = request.getRequestURI();
        // 管理后台登录校验放行（校验在处理器内完成），其余 /api/admin/** 需鉴权
        return !uri.startsWith("/api/admin/") || uri.equals("/api/admin/login");
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        String provided = request.getHeader("X-Admin-Key");
        if (provided == null || !MessageDigest.isEqual(
                expectedSha256.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                sha256(provided).getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"error\":\"管理后台认证失败\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}