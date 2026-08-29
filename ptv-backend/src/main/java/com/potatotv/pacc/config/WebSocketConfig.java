package com.potatotv.pacc.config;

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
 */
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    public static final String PLAYER_ENDPOINT = "/ws/ptv";

    private final PlayerWebSocketHandler playerWebSocketHandler;
    private final PlayerHandshakeInterceptor handshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(@NonNull WebSocketHandlerRegistry registry) {
        registry.addHandler(playerWebSocketHandler, PLAYER_ENDPOINT)
                .addInterceptors(handshakeInterceptor)
                .setAllowedOrigins("*"); // 生产环境建议收窄至 PTV 客户端配置的固定来源
    }
}