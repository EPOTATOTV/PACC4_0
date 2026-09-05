package com.potatotv.pacc.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 远程查端信令总线：{@code sessionId → {玩家腿, 管理端腿}} 的双向转发注册表。
 * <p>管理端在 {@code /ws/admin} 连接成功时注册其腿，玩家腿在 {@code InspectService.start}
 * 下发查端请求时按 PTEID 定位在线玩家会话注册。任一腿断开即清理整个会话，防止泄漏。
 * 供乐队：玩家 → 管理端、管理端 → 玩家的信令原样透传，不解析业务语义。</p>
 * <p>进程内单实例实现；生产多实例部署时应改为 Redis pub/sub（同 {@code LoginThrottle} 风格）。</p>
 */
@Slf4j
@Component
public class InspectSignalBus {

    /** 一条查端会话的两条信令腿。 */
    private static final class Legs {
        volatile WebSocketSession playerSession;
        volatile WebSocketSession adminSession;
        final String pteid;

        Legs(WebSocketSession playerSession, String pteid) {
            this.playerSession = playerSession;
            this.pteid = pteid;
        }
    }

    private final Map<String, Legs> sessions = new ConcurrentHashMap<>();
    /** WebSocket 会话 ID → 查端会话 ID，用于腿断开时反查并清理。 */
    private final Map<String, String> sessionIdByWss = new ConcurrentHashMap<>();

    /** 注册（或更新）玩家腿。玩家离线时传入 null 占位，待管理端连接建腿。 */
    public void registerPlayer(@NonNull String sessionId, WebSocketSession playerSession, String pteid) {
        Legs legs = sessions.computeIfAbsent(sessionId, k -> new Legs(playerSession, pteid));
        synchronized (legs) {
            legs.playerSession = playerSession;
        }
        if (playerSession != null) {
            sessionIdByWss.put(playerSession.getId(), sessionId);
            log.info("信令总线注册玩家腿 sessionId={} pteid={} playerWs={}", sessionId, pteid, playerSession.getId());
        }
    }

    /** 注册管理端腿。同一条会话可被多个管理端连接，后连者覆盖。 */
    public void registerAdmin(@NonNull String sessionId, @NonNull WebSocketSession adminSession) {
        Legs legs = sessions.computeIfAbsent(sessionId, k -> new Legs(null, ""));
        synchronized (legs) {
            legs.adminSession = adminSession;
        }
        sessionIdByWss.put(adminSession.getId(), sessionId);
        log.info("信令总线注册管理端腿 sessionId={} adminWs={}", sessionId, adminSession.getId());
    }

    /** 反查一条 WebSocket 会话所属的查端会话 ID；无效返回空。 */
    public Optional<String> sessionIdFor(WebSocketSession wss) {
        return Optional.ofNullable(sessionIdByWss.get(wss.getId()));
    }

    public Optional<WebSocketSession> playerSessionOf(String sessionId) {
        Legs legs = sessions.get(sessionId);
        if (legs == null) return Optional.empty();
        return legs.playerSession == null ? Optional.empty() : Optional.of(legs.playerSession);
    }

    /** 玩家 → 管理端：把玩家上报的信令原样转给对应管理端腿。 */
    public boolean forwardPlayerToAdmin(@NonNull WebSocketSession playerWss, @NonNull String json) {
        Optional<String> sid = sessionIdFor(playerWss);
        if (sid.isEmpty()) return false;
        Legs legs = sessions.get(sid.get());
        if (legs == null || legs.adminSession == null) return false;
        return send(legs.adminSession, json);
    }

    /** 管理端 → 玩家：把管理端下发的信令原样转给对应玩家腿。 */
    public boolean forwardAdminToPlayer(@NonNull WebSocketSession adminWss, @NonNull String json) {
        Optional<String> sid = sessionIdFor(adminWss);
        if (sid.isEmpty()) return false;
        Legs legs = sessions.get(sid.get());
        if (legs == null || legs.playerSession == null) return false;
        return send(legs.playerSession, json);
    }

    /** 给指定查端会话的玩家腿下发一条信令（用于查端请求 / 结束）。 */
    public boolean sendToPlayer(String sessionId, @NonNull String json) {
        Legs legs = sessions.get(sessionId);
        if (legs == null || legs.playerSession == null) return false;
        return send(legs.playerSession, json);
    }

    /** 给指定查端会话的管理端腿下发一条信令。 */
    public boolean sendToAdmin(String sessionId, @NonNull String json) {
        Legs legs = sessions.get(sessionId);
        if (legs == null || legs.adminSession == null) return false;
        return send(legs.adminSession, json);
    }

    /** 腿断开清理：移除该连接所属会话；另一条腿仍存在则保留（服务端可重连补齐）。 */
    public void unregister(WebSocketSession wss) {
        String sessionId = sessionIdByWss.remove(wss.getId());
        if (sessionId == null) return;
        Legs legs = sessions.get(sessionId);
        if (legs == null) return;
        synchronized (legs) {
            if (legs.adminSession == wss) legs.adminSession = null;
            if (legs.playerSession == wss) legs.playerSession = null;
            if (legs.adminSession == null && legs.playerSession == null) {
                sessions.remove(sessionId);
            }
        }
        log.info("信令总线清理腿 sessionId={} ws={}", sessionId, wss.getId());
    }

    private boolean send(WebSocketSession session, String json) {
        if (session == null || !session.isOpen()) return false;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(json));
            }
            return true;
        } catch (IOException e) {
            log.warn("信令转发失败 ws={} err={}", session.getId(), e.getMessage());
            return false;
        }
    }
}