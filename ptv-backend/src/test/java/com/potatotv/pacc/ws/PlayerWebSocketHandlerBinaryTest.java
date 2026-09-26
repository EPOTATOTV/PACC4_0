package com.potatotv.pacc.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.InspectSignalBus;
import com.potatotv.pacc.service.MapBpEventBus;
import com.potatotv.pacc.service.OnlineStatusService;
import com.potatotv.pacc.service.RedscreenService;
import com.potatotv.pacc.service.RiskScoringService;
import com.potatotv.pbp.gen.PaccEnvelope;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 玩家端处理器二进制（PBP 信封）路径单测：验签通过的信令转发给查端总线。 */
class PlayerWebSocketHandlerBinaryTest {

    private final PaccWireCodec codec = new PaccWireCodec("test-secret", 60_000, new WssSessionKeys(false));

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

        PlayerWebSocketHandler h = new PlayerWebSocketHandler(new ObjectMapper(), online, risk, red, acc, guard, bus, new MapBpEventBus(new ObjectMapper()), codec, new WssSessionKeys(false));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of("pteid", "PT01"));
        when(session.getId()).thenReturn("s1");

        PaccEnvelope env = PaccWireCodec.build("inspect_forensics", "sess", "PT01",
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
        PlayerWebSocketHandler h = new PlayerWebSocketHandler(new ObjectMapper(), online, risk, red, acc, guard, bus, new MapBpEventBus(new ObjectMapper()), codec, new WssSessionKeys(false));

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of("pteid", "PT02"));
        when(session.getId()).thenReturn("s2");

        // 篡改 payload 后签名不匹配 → 应被拒绝，不转发
        PaccEnvelope env = PaccWireCodec.build("inspect_forensics", "sess", "PT02", "{}", "test-secret")
                .toBuilder().setPayloadJson("{\"os\":\"hijacked\"}").build();
        h.handleBinaryMessage(session, new BinaryMessage(env.toByteArray()));

        verify(bus, org.mockito.Mockito.never())
                .forwardPlayerToAdmin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * 会话密钥握手：session_init 用静态密钥（v1）引导，服务端回 session_ready（v2 会话密钥），
     * 之后用会话密钥签的 inspect_* 才能通过验签。
     */
    @Test
    void sessionInitEstablishesKeyThenV2EnvelopeForwards() throws Exception {
        OnlineStatusService online = mock(OnlineStatusService.class);
        RiskScoringService risk = mock(RiskScoringService.class);
        RedscreenService red = mock(RedscreenService.class);
        AccountService acc = mock(AccountService.class);
        WssMessageGuard guard = mock(WssMessageGuard.class);
        InspectSignalBus bus = mock(InspectSignalBus.class);
        when(bus.forwardPlayerToAdmin(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(true);
        WssSessionKeys keys = new WssSessionKeys(false);
        PaccWireCodec wire = new PaccWireCodec("test-secret", 60_000, keys);
        PlayerWebSocketHandler h = new PlayerWebSocketHandler(new ObjectMapper(), online, risk, red, acc, guard,
                bus, new MapBpEventBus(new ObjectMapper()), wire, keys);

        WebSocketSession session = mock(WebSocketSession.class);
        // 必须可变：处理器会把会话密钥 id 记进 attributes，供断线时销毁
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("pteid", "PT01");
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("s1");

        String sid = "sid-handshake";
        String salt = "00112233445566778899aabbccddeeff";
        h.handleBinaryMessage(session, new BinaryMessage(PaccWireCodec
                .build(WssSessionKeys.INIT_TYPE, sid, "PT01", "{\"salt\":\"" + salt + "\"}", "test-secret")
                .toByteArray()));

        // 会话已登记，且服务端回了一枚 session_ready
        assertNotNull(keys.get(sid));
        ArgumentCaptor<BinaryMessage> sent = ArgumentCaptor.forClass(BinaryMessage.class);
        verify(session).sendMessage(sent.capture());
        PaccEnvelope ready = PaccEnvelope.parseFrom(sent.getValue().getPayload().array());
        assertEquals(WssSessionKeys.READY_TYPE, ready.getType());
        assertEquals(WssSessionKeys.SIG_V2, ready.getSigVersion());
        assertEquals(sid, ready.getSessionId());

        // 用同一会话密钥签发的 inspect_* 应被转发
        String sessionKey = keys.get(sid).keyHex();
        h.handleBinaryMessage(session, new BinaryMessage(wire
                .buildWithSessionKey("inspect_forensics", sid, "PT01", "{\"os\":\"win\"}", sessionKey)
                .toByteArray()));
        verify(bus).forwardPlayerToAdmin(org.mockito.ArgumentMatchers.eq(session),
                org.mockito.ArgumentMatchers.contains("win"));
    }

    /** 断开连接必须销毁会话密钥，避免密钥在内存里滞留到 TTL。 */
    @Test
    void connectionClosedDestroysSessionKey() throws Exception {
        OnlineStatusService online = mock(OnlineStatusService.class);
        WssSessionKeys keys = new WssSessionKeys(false);
        keys.open("sid-drop", "00112233445566778899aabbccddeeff", "PT09", "test-secret");
        PlayerWebSocketHandler h = new PlayerWebSocketHandler(new ObjectMapper(), online,
                mock(RiskScoringService.class), mock(RedscreenService.class), mock(AccountService.class),
                mock(WssMessageGuard.class), mock(InspectSignalBus.class), new MapBpEventBus(new ObjectMapper()),
                new PaccWireCodec("test-secret", 60_000, keys), keys);

        WebSocketSession session = mock(WebSocketSession.class);
        java.util.Map<String, Object> attrs = new java.util.HashMap<>();
        attrs.put("pteid", "PT09");
        attrs.put("paccSessionId", "sid-drop");
        when(session.getAttributes()).thenReturn(attrs);
        when(session.getId()).thenReturn("s9");

        h.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);

        assertNull(keys.get("sid-drop"));
    }
}