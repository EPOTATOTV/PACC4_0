package com.potatotv.pacc.ws;

import com.potatotv.pacc.service.AdminTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 管理端信令 WebSocket 握手拦截器：校验 {@code session_id} 查询参数与管理员身份。
 * <p>管理员身份沿用 {@link AdminKeyFilter} 的同款方案：浏览器管理后台凭 HttpOnly 会话
 * cookie（{@code pacc_admin}）由同源 WS 握手自动附带；桌面工具等可从 {@code X-Admin-Key}
 * 请求头带入会话令牌。二者任一有效即放行并绑定 sessionId。</p>
 */
@Component
@RequiredArgsConstructor
public class AdminSignalHandshakeInterceptor implements HandshakeInterceptor {

    private static final String ADMIN_COOKIE = "pacc_admin";

    private final AdminTokenService adminTokenService;

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
        Map<String, String> params = queryParams(request.getURI().getRawQuery());
        String sessionId = params.get("session_id");
        if (sessionId == null || sessionId.isBlank()) return false;

        // 认证：cookie 会话令牌 或 X-Admin-Key 头带同一 JWT，二选一有效即可
        Map<String, String> cookies = cookies(request);
        String token = cookies.getOrDefault(ADMIN_COOKIE, request.getHeaders().getFirst("X-Admin-Key"));
        if (token == null || token.isBlank()) return false;
        if (adminTokenService.parseRole(token) == null) return false;

        attributes.put("session_id", sessionId);
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler, @Nullable Exception exception) {
    }

    private static Map<String, String> queryParams(String q) {
        Map<String, String> map = new HashMap<>();
        if (q == null) return map;
        for (String pair : q.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(decode(pair.substring(0, i)), decode(pair.substring(i + 1)));
            }
        }
        return map;
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    private static Map<String, String> cookies(ServerHttpRequest request) {
        Map<String, String> map = new HashMap<>();
        String header = request.getHeaders().getFirst("Cookie");
        if (header == null || header.isBlank()) return map;
        for (String part : header.split(";")) {
            int i = part.indexOf('=');
            if (i > 0) {
                map.put(part.substring(0, i).trim(), part.substring(i + 1).trim());
            }
        }
        return map;
    }

    /** 登录签发使用的来源指纹同款实现（保留提示：生产勿用于日志）。 */
    @SuppressWarnings("unused")
    private static String fp(String ip, String ua) {
        String digest;
        try {
            digest = hex(MessageDigest.getInstance("SHA-256")
                    .digest((ip + "|" + ua).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            digest = "";
        }
        return digest == null ? "" : digest;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }
}