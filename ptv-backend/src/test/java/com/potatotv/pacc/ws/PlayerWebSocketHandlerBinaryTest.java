package com.potatotv.pacc.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.proto.PaccWire;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.InspectSignalBus;
import com.potatotv.pacc.service.MapBpEventBus;
import com.potatotv.pacc.service.OnlineStatusService;
import com.potatotv.pacc.service.RedscreenService;
import com.potatotv.pacc.service.RiskScoringService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 玩家端处理器二进制（protobuf 信封）路径单测：验签通过的信令转发给查端总线。 */
class PlayerWebSocketHandlerBinaryTest {

    private final PaccWireCodec codec = new PaccWireCodec("test-secret", 60_000);

    @Test
    void verifiedInspectEnvelopeForwardsToAdmin() throws Exception {
        OnlineStatusService online = mock(OnlineStatusService.class);
        RiskScoringService risk = mock(RiskScoringService.class);
        RedscreenService red = mock(RedscreenService.class);
        AccountService acc = mock(AccountService.class);
        WssMessageGuard guard = mock(WssMessageGuard.class);
        InspectSignalBus bus = mock(InspectSignalBus.class);
        when(bus.forwardPlayerToAdmin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);

        PlayerWebSocketHandler h = new PlayerWebSocketHandler(new ObjectMapper(), online, risk, red, acc, guard, bus, new MapBpEventBus(new ObjectMapper()), codec);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of("pteid", "PT01"));
        when(session.getId()).thenReturn("s1");

        PaccWire.WsEnvelope env = PaccWireCodec.build("inspect_forensics", "sess", "PT01",
                "{\"os\":\"win\",\"processes\":[{\"pid\":1,\"name\":\"a\"}]}", "test-secret");

        h.handleBinaryMessage(session, new BinaryMessage(env.toByteArray()));

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(bus).forwardPlayerToAdmin(org.mockito.ArgumentMatchers.eq(session), cap.capture());
        assertTrue(cap.getValue().contains("inspect_forensics") || cap.getValue().contains("win"));
    }

    @Test
    void tamperedEnvelopeRejectedAndNotForwarded() throws Exception {
        OnlineStatusService online = mock(OnlineStatusService.class);
        RiskScoringService risk = mock(RiskScoringService.class);
        RedscreenService red = mock(RedscreenService.class);
        AccountService acc = mock(AccountService.class);
        WssMessageGuard guard = mock(WssMessageGuard.class);
        InspectSignalBus bus = mock(InspectSignalBus.class);
        PlayerWebSocketHandler h = new PlayerWebSocketHandler(new ObjectMapper(), online, risk, red, acc, guard, bus, new MapBpEventBus(new ObjectMapper()), codec);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of("pteid", "PT02"));
        when(session.getId()).thenReturn("s2");

        // 篡改 payload 后签名不匹配 → 应被拒绝，不转发
        PaccWire.WsEnvelope env = PaccWireCodec.build("inspect_forensics", "sess", "PT02", "{}", "test-secret")
                .toBuilder().setPayloadJson("{\"os\":\"hijacked\"}").build();
        h.handleBinaryMessage(session, new BinaryMessage(env.toByteArray()));

        verify(bus, org.mockito.Mockito.never())
                .forwardPlayerToAdmin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }
}