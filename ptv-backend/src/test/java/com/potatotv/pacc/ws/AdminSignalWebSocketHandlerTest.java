package com.potatotv.pacc.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.service.InspectSignalBus;
import com.potatotv.pacc.service.MapBpEventBus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 管理端信令处理器路由单测：建腿、inspect_* 透传给玩家。 */
class AdminSignalWebSocketHandlerTest {

    private WebSocketSession wss(String id, Map<String, Object> attrs) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(attrs);
        return s;
    }

    @Test
    void inspectIceForwardsToPlayerAndPingDoesNot() throws Exception {
        InspectSignalBus bus = new InspectSignalBus();
        WebSocketSession player = wss("player", Map.of("pteid", "PT01"));
        WebSocketSession admin = wss("admin", Map.of("session_id", "s9"));

        bus.registerPlayer("s9", player, "PT01");
        AdminSignalWebSocketHandler h = new AdminSignalWebSocketHandler(bus, new MapBpEventBus(new ObjectMapper()), new ObjectMapper());
        h.afterConnectionEstablished(admin);

        h.handleMessage(admin, new TextMessage("{\"type\":\"inspect_ice\",\"sdp\":\"x\"}"));
        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(player).sendMessage(cap.capture());
        assertTrue(cap.getValue().getPayload().contains("inspect_ice"));

        // 未知/非透传类型（ping）只回 pong 给管理端，不向玩家发：玩家端仍只有 1 次发送
        h.handleMessage(admin, new TextMessage("{\"type\":\"ping\"}"));
        verify(player, times(1)).sendMessage(any(TextMessage.class));
    }
}