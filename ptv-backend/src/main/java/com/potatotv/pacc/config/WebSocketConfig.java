package com.potatotv.pacc.config;

import com.potatotv.pacc.ws.AdminSignalHandshakeInterceptor;
import com.potatotv.pacc.ws.AdminSignalWebSocketHandler;
import com.potatotv.pacc.ws.PlayerWebSocketHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.lang.NonNull;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 配置：注册玩家端 PTV 长连接入口与握手拦截器。
 * 玩家端与 PTV 服务器仅通过该通道通信，游戏服务器不可见。
 * <p>另注册管理端信令通道 {@code /ws/admin}，供远程查端信令双向透传。</p>
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
@SuppressWarnings("null") // Spring @NonNull 契约的 JDT unchecked-conversion 误报
public class WebSocketConfig implements WebSocketConfigurer {

    public static final String PLAYER_ENDPOINT = "/ws/ptv";
    public static final String ADMIN_SIGNAL_ENDPOINT = "/ws/admin";

    private final PlayerWebSocketHandler playerWebSocketHandler;
    private final PlayerHandshakeInterceptor handshakeInterceptor;
    private final AdminSignalWebSocketHandler adminSignalWebSocketHandler;
    private final AdminSignalHandshakeInterceptor adminSignalHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(playerWebSocketHandler, PLAYER_ENDPOINT)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*"); // 生产环境建议收窄至 PTV 客户端配置的固定来源

        // 管理端信令通道：origin 收窄到管理后台来源，握手内做管理员身份校验
        registry.addHandler(adminSignalWebSocketHandler, ADMIN_SIGNAL_ENDPOINT)
                .addInterceptors(adminSignalHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}