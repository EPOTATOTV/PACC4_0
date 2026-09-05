package com.potatotv.pacc.service;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 查端信令总线单测：双腿注册、双向转发、断链清理（WebSocketSession 用 Mockito 桩）。 */
class InspectSignalBusTest {

    private final InspectSignalBus bus = new InspectSignalBus();

    private WebSocketSession session(String id) {
        WebSocketSession s = mock(WebSocketSession.class);
        when(s.getId()).thenReturn(id);
        when(s.isOpen()).thenReturn(true);
        when(s.getAttributes()).thenReturn(Map.of());
        return s;
    }

    @Test
    void playerToAdminForwardDeliversRawJson() throws Exception {
        WebSocketSession player = session("p1");
        WebSocketSession admin = session("a1");
        bus.registerPlayer("s1", player, "PT123");
        bus.registerAdmin("s1", admin);

        assertTrue(bus.forwardPlayerToAdmin(player, "{\"type\":\"inspect_forensics\"}"));
        ArgumentCaptor<TextMessage> cap = ArgumentCaptor.forClass(TextMessage.class);
        verify(admin).sendMessage(cap.capture());
        assertTrue(cap.getValue().getPayload().contains("inspect_forensics"));
    }

    @Test
    void adminToPlayerForwardDelivers() throws Exception {
        WebSocketSession player = session("p2");
        WebSocketSession admin = session("a2");
        bus.registerPlayer("s2", player, "PT456");
        bus.registerAdmin("s2", admin);

        assertTrue(bus.forwardAdminToPlayer(admin, "{\"type\":\"inspect_ice\"}"));
        verify(player).sendMessage(any(TextMessage.class));
    }

    @Test
    void forwardFailsWhenCounterpartMissing() throws Exception {
        WebSocketSession player = session("p3");
        bus.registerPlayer("s3", player, "PT789");
        // 只有玩家腿，管理端未连接 → 玩家→管理端转发失败但不抛
        assertFalse(bus.forwardPlayerToAdmin(player, "{\"type\":\"x\"}"));
        verify(player, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void unregisterCleansWhenBothLegsGone() {
        WebSocketSession player = session("p4");
        WebSocketSession admin = session("a4");
        bus.registerPlayer("s4", player, "PT0");
        bus.registerAdmin("s4", admin);

        bus.unregister(player);
        assertTrue(bus.sessionIdFor(admin).isPresent()); // 管理端腿还在
        bus.unregister(admin);
        assertTrue(bus.sessionIdFor(player).isEmpty());
        assertTrue(bus.sessionIdFor(admin).isEmpty());
    }
}