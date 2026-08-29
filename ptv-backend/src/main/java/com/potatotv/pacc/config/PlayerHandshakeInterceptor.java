package com.potatotv.pacc.config;

import com.potatotv.pacc.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 玩家端 WebSocket 握手拦截器：从 URL 查询参数校验 PTEID 与携带的访问令牌。
 */
@Component
@RequiredArgsConstructor
public class PlayerHandshakeInterceptor implements HandshakeInterceptor {

    private final TokenService tokenService;

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler, @NonNull Map<String, Object> attributes) {
        var params = request.getURI().getQuery() == null ? Map.<String, String>of() : queryParams(request.getURI().getQuery());
        String pteid = params.get("pteid");
        String token = params.get("token");
        String edition = params.getOrDefault("edition", "UNKNOWN");
        if (pteid == null || token == null) return false;
        try {
            // 令牌主体必须与声明的 PTEID 一致，防止跨账号冒用
            String subject = tokenService.verify(token);
            if (!pteid.equals(subject)) return false;
        } catch (Exception e) {
            return false;
        }
        attributes.put("pteid", pteid);
        attributes.put("edition", edition);
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request, @NonNull ServerHttpResponse response,
                               @NonNull WebSocketHandler wsHandler, @Nullable Exception exception) {
    }

    private Map<String, String> queryParams(String query) {
        Map<String, String> map = new java.util.HashMap<>();
        for (String pair : query.split("&")) {
            int i = pair.indexOf('=');
            if (i > 0) {
                map.put(decode(pair.substring(0, i)), decode(pair.substring(i + 1)));
            }
        }
        return map;
    }

    private String decode(String v) {
        return java.net.URLDecoder.decode(v, StandardCharsets.UTF_8);
    }
}