package com.potatotv.pacc.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.service.InspectSignalBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * 管理端信令 WebSocket 处理器（{@code /ws/admin}）。
 * <p>管理端浏览器在开始查端后建立本连接并凭 {@code session_id} 绑腿，用于：
 * 接收玩家端回传的 {@code inspect_started} / {@code inspect_forensics} 等取证信令，
 * 并把管理端的 {@code inspect_answer} / {@code inspect_ice} 等信令透传给玩家端。
 * 本轮（B1）为纯 JSON 文本透传，不消费 WebRTC 语义。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminSignalWebSocketHandler extends TextWebSocketHandler {

    private final InspectSignalBus signalBus;
    private final ObjectMapper mapper;

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String sessionId = (String) session.getAttributes().get("session_id");
        if (sessionId != null) {
            signalBus.registerAdmin(sessionId, session);
            log.info("管理端信令上线 sessionId={} adminWs={}", sessionId, session.getId());
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String sessionId = (String) session.getAttributes().get("session_id");
        if (sessionId == null) return;
        try {
            String type = mapper.readTree(message.getPayload()).path("type").asText("");
            switch (type) {
                case "ping" -> send(session, "{\"type\":\"pong\"}");
                case "inspect_answer", "inspect_ice", "inspect_cancel", "inspect_forces" ->
                        signalBus.forwardAdminToPlayer(session, message.getPayload());
                default -> log.debug("未知管理端信令 type={} sessionId={}", type, sessionId);
            }
        } catch (Exception e) {
            log.warn("解析管理端信令失败 sessionId={} err={}", sessionId, e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        signalBus.unregister(session);
        log.info("管理端信令下线 ws={}", session.getId());
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.warn("管理端信令传输异常 ws={} err={}", session.getId(), exception.getMessage());
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {
            // 关闭失败忽略
        }
        signalBus.unregister(session);
    }

    private void send(WebSocketSession session, String json) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (Exception e) {
            log.warn("管理端信令发送失败 ws={} err={}", session.getId(), e.getMessage());
        }
    }
}