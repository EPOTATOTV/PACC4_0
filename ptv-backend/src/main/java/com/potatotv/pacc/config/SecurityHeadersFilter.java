package com.potatotv.pacc.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头加固：为所有 API 响应附加基础防护头，降低浏览器侧攻击面
 * （MIME 嗅探、frame 点击劫持、referrer 泄露、权限能力、缓存敏感数据）。
 * <p>版本无关实现：直接写入 {@link HttpServletResponse}，避免依赖具体
 * Spring Security 头 DSL 的版本差异。</p>
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        // MIME 类型嗅探
        setIfAbsent(response, "X-Content-Type-Options", "nosniff");
        // 点击劫持防护（同源 frame）
        setIfAbsent(response, "X-Frame-Options", "SAMEORIGIN");
        // referrer 策略：杜绝把内网/令牌信息经 Referer 发给第三方
        setIfAbsent(response, "Referrer-Policy", "no-referrer");
        // 权限能力：默认禁用相机/麦克风/地理定位（反作弊进程不应使用这些能力）
        setIfAbsent(response, "Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        // HSTS：强制 HTTPS（浏览器在明文 HTTP 下忽略该头，生产经 nginx 注入相同头保持幂等）
        setIfAbsent(response, "Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        // 动态 API 响应一律不缓存，防止认证数据残留
        setIfAbsent(response, "Cache-Control", "no-store, no-cache, must-revalidate");
        setIfAbsent(response, "Pragma", "no-cache");
        chain.doFilter(request, response);
    }

    private static void setIfAbsent(HttpServletResponse response, String name, String value) {
        if (response.getHeader(name) == null) {
            response.setHeader(name, value);
        }
    }
}