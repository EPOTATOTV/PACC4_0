package com.potatotv.pacc.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 统一访问日志（安全脱敏）。
 * <p>仅记录 method / path / status / 耗时 / 来源 IP。
 * <b>绝不记录</b>请求行、查询串（避免泄露 WebSocket token 或查询参数 PII）、
 * 请求头与请求体（避免泄露 Authorization / X-Admin-Key）。</p>
 */
public class AccessLogFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("ACCESS");

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request, @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain) throws ServletException, IOException {
        long start = System.nanoTime();
        Throwable error = null;
        try {
            chain.doFilter(request, response);
        } catch (Throwable t) {
            error = t;
            throw t;
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            int status = response.getStatus();
            String line = String.format("method=%s path=%s status=%d duration=%dms client=%s",
                    request.getMethod(), sanitize(request.getRequestURI()), status, ms, sink(request.getRemoteAddr()));
            if (status >= 500) {
                log.error(line);
            } else if (status >= 400) {
                log.warn(line);
            } else if (error == null) {
                log.info(line);
            }
        }
    }

    /** 只保留短横线/字母数字与斜杠，其他控制字符一律替换，防日志注入。 */
    private static String sanitize(String s) {
        if (s == null) return "-";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t' || c < 0x20 || c == 0x7f) {
                sb.append('?');
            } else {
                sb.append(c);
            }
        }
        return sb.isEmpty() ? "-" : sb.toString();
    }

    /** 空值兜底，避免日志出现 null。 */
    private static String sink(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }
}