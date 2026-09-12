package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 地图 BP 实时事件总线：{@code bpSessionId → 订阅的 WebSocket 会话集合} 广播注册表。
 * <p>玩家端与裁判端分别经各自长连接（{@code /ws/ptv} 与 {@code /ws/admin}）发送
 * {@code bp_subscribe} 订阅某 BP 会话；此后该会话的任意参与者/裁判都能收到
 * {@code bp_state / bp_action / bp_turn_change / bp_completed / bp_status} 事件推送。
 * 事件以 JSON 文本下发（type + payload 合并），沿用现有字符串 type 路由，不改 protobuf。</p>
 * <p>进程内单实例实现；生产多实例部署时应改为 Redis pub/sub（同 {@code LoginThrottle} 风格）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MapBpEventBus {

    private final ObjectMapper mapper;

    /** bpSessionId → 已订阅会话。 */
    private final Map<String, Set<WebSocketSession>> subscribers = new ConcurrentHashMap<>();
    /** WebSocket 会话 ID → bpSessionId，用于断开时反查清理。 */
    private final Map<String, String> bpIdByWss = new ConcurrentHashMap<>();

    /** 订阅一个 BP 会话的实时事件。 */
    public void subscribe(@NonNull String bpSessionId, @NonNull WebSocketSession session) {
        subscribers.computeIfAbsent(bpSessionId, k -> ConcurrentHashMap.newKeySet()).add(session);
        bpIdByWss.put(session.getId(), bpSessionId);
    }

    /** 取消订阅某个 BP 会话的实时事件。 */
    public void unsubscribe(@NonNull String bpSessionId, @NonNull WebSocketSession session) {
        Set<WebSocketSession> set = subscribers.get(bpSessionId);
        if (set != null) set.remove(session);
        bpIdByWss.remove(session.getId());
    }

    /** 会话断开：反查其订阅的 BP 会话并清理，避免悬挂引用。 */
    public void onDisconnect(@NonNull WebSocketSession session) {
        String bpId = bpIdByWss.remove(session.getId());
        if (bpId == null) return;
        Set<WebSocketSession> set = subscribers.get(bpId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) subscribers.remove(bpId);
        }
    }

    /** 向某 BP 会话的所有订阅者广播事件。 */
    public void publish(String bpSessionId, String type, Map<String, Object> payload) {
        if (bpSessionId == null) return;
        Set<WebSocketSession> set = subscribers.get(bpSessionId);
        if (set == null || set.isEmpty()) return;
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", type);
        if (payload != null) msg.putAll(payload);
        String json;
        try {
            json = mapper.writeValueAsString(msg);
        } catch (Exception e) {
            log.warn("BP 事件序列化失败 bp={} type={} err={}", bpSessionId, type, e.getMessage());
            return;
        }
        for (WebSocketSession s : set) {
            if (s == null || !s.isOpen()) {
                set.remove(s);
                continue;
            }
            try {
                synchronized (s) {
                    s.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.warn("BP 事件推送失败 bp={} type={} ws={} err={}",
                        bpSessionId, type, s.getId(), e.getMessage());
            }
        }
    }
}