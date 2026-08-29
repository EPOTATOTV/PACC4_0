package com.potatotv.pacc.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 玩家端 WebSocket 长连接注册表（在线玩家列表）。
 * 红屏广播基于此集合实现全在线并行推送。
 */
@Component
public class OnlineStatusService {

    private static final Logger log = LoggerFactory.getLogger(OnlineStatusService.class);

    /** pteid -> 会话集合（同一账号多设备）。 */
    private final Map<String, CopyOnWriteArraySet<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public int onlineCount() {
        return sessions.values().stream().mapToInt(CopyOnWriteArraySet::size).sum();
    }

    public void register(String pteid, WebSocketSession session) {
        sessions.computeIfAbsent(pteid, k -> new CopyOnWriteArraySet<>()).add(session);
    }

    public void unregister(String pteid, WebSocketSession session) {
        CopyOnWriteArraySet<WebSocketSession> set = sessions.get(pteid);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) sessions.remove(pteid);
        }
    }

    /** 返回指定 PTEID 的任一在线会话（用于定向下发）。 */
    public java.util.Optional<WebSocketSession> firstSession(String pteid) {
        CopyOnWriteArraySet<WebSocketSession> set = sessions.get(pteid);
        if (set == null || set.isEmpty()) return java.util.Optional.empty();
        return set.stream().filter(WebSocketSession::isOpen).findFirst();
    }

    /** 广播给所有在线玩家会话。返回成功送达数。 */
    public long broadcast(String payload) {
        long ack = 0;
        for (Map.Entry<String, CopyOnWriteArraySet<WebSocketSession>> e : sessions.entrySet()) {
            for (WebSocketSession s : e.getValue()) {
                try {
                    if (s.isOpen()) {
                        synchronized (s) {
                            s.sendMessage(new TextMessage(payload));
                        }
                        ack++;
                    }
                } catch (IOException ex) {
                    log.warn("广播失败 session={} pteid={} err={}", s.getId(), e.getKey(), ex.getMessage());
                }
            }
        }
        return ack;
    }
}