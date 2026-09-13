package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Notification;
import com.potatotv.pacc.domain.NotificationSetting;
import com.potatotv.pacc.domain.PlayerNotifRead;
import com.potatotv.pacc.repository.NotificationRepository;
import com.potatotv.pacc.repository.NotificationSettingRepository;
import com.potatotv.pacc.repository.PlayerNotifReadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一通知服务：管理端公告/定向通知落库 + WS 实时推送。
 * 与派生态通知（红屏/申诉/工单）并存，玩家端通知中心合并展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 存储层返回值的 null 分析误报
public class NotificationService {

    private final NotificationRepository notificationRepository;
    private final NotificationSettingRepository settingRepository;
    private final PlayerNotifReadRepository notifReadRepository;
    private final OnlineStatusService onlineStatusService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 广播公告：落库并向在线玩家 WS 推送 notify 帧。 */
    @Transactional
    public Notification sendToAll(String title, String content, String scope, String type, String priority) {
        Notification n = notificationRepository.save(Notification.builder()
                .id(UUID.randomUUID().toString())
                .pteid(null)
                .scope(scope == null || scope.isBlank() ? "SYSTEM" : scope)
                .type(type == null || type.isBlank() ? "SYSTEM_ANNOUNCEMENT" : type)
                .title(title)
                .content(content)
                .priority(priority == null || priority.isBlank() ? "NORMAL" : priority)
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build());
        push(n);
        return n;
    }

    /** 定向通知：落库并仅推送给目标玩家在线会话。 */
    @Transactional
    public Notification sendToOne(String pteid, String title, String content, String scope, String type, String priority) {
        Notification n = notificationRepository.save(Notification.builder()
                .id(UUID.randomUUID().toString())
                .pteid(pteid)
                .scope(scope == null || scope.isBlank() ? "SYSTEM" : scope)
                .type(type == null || type.isBlank() ? "SYSTEM_ANNOUNCEMENT" : type)
                .title(title)
                .content(content)
                .priority(priority == null || priority.isBlank() ? "NORMAL" : priority)
                .status("ACTIVE")
                .createdAt(Instant.now())
                .build());
        push(n);
        return n;
    }

    /** 通知中心列表：广播 + 定向合并，按时间倒序。 */
    public List<Map<String, Object>> list(String pteid, String kind) {
        List<Notification> merged = new java.util.ArrayList<>(
                notificationRepository.findByPteidIsNullOrderByCreatedAtDesc());
        merged.addAll(notificationRepository.findByPteidOrderByCreatedAtDesc(pteid));
        Instant now = Instant.now();
        return merged.stream()
                .filter(n -> "ACTIVE".equals(n.getStatus()))
                .filter(n -> n.getExpiresAt() == null || n.getExpiresAt().isAfter(now))
                .filter(n -> kind == null || kind.isBlank() || kind.equals(kindOf(n)))
                .sorted(java.util.Comparator.comparing(Notification::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .map(n -> toView(n, pteid))
                .collect(Collectors.toList());
    }

    private Map<String, Object> toView(Notification n, String pteid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", "nt-" + n.getId());
        m.put("kind", kindOf(n));
        m.put("scope", n.getScope());
        m.put("title", n.getTitle());
        m.put("body", n.getContent());
        m.put("priority", n.getPriority());
        m.put("read", readIds(pteid).contains("nt-" + n.getId()));
        m.put("createdAt", n.getCreatedAt() == null ? "" : n.getCreatedAt().toString());
        return m;
    }

    /** 将后端通知类型映射为前端通知中心 kind（公告 → system），其余原样小写化。 */
    private String kindOf(Notification n) {
        String type = n.getType() == null ? "" : n.getType();
        return "SYSTEM_ANNOUNCEMENT".equals(type) ? "system" : type.toLowerCase();
    }

    /** 未读统计：仅统计当前 pteid 可见的通知（广播 + 定向）。 */
    public long unreadCount(String pteid) {
        return list(pteid, null).stream().filter(m -> !Boolean.TRUE.equals(m.get("read"))).count();
    }

    @Transactional
    public void markRead(String pteid, String notifId) {
        if (pteid.isEmpty() || notifId == null || notifId.isBlank()) return;
        if (readIds(pteid).contains(notifId)) return;
        notifReadRepository.save(PlayerNotifRead.builder()
                .id(UUID.randomUUID().toString())
                .pteid(pteid)
                .notifId(notifId)
                .readAt(Instant.now())
                .build());
    }

    /** 通知偏好：读取（无记录返回默认 JSON）。 */
    public Map<String, Object> getSettings(String pteid) {
        NotificationSetting s = settingRepository.findById(pteid).orElse(null);
        if (s == null || s.getSettings() == null || s.getSettings().isBlank()) {
            return defaultSettings();
        }
        try {
            Map<String, Object> map = objectMapper.readValue(s.getSettings(), new com.fasterxml.jackson.core.type.TypeReference<>() {
            });
            return map == null ? defaultSettings() : map;
        } catch (Exception e) {
            return defaultSettings();
        }
    }

    @Transactional
    public Map<String, Object> updateSettings(String pteid, Map<String, Object> body) {
        Map<String, Object> merged = new LinkedHashMap<>(getSettings(pteid));
        body.forEach((k, v) -> {
            if (v != null) merged.put(k, v);
        });
        try {
            settingRepository.save(NotificationSetting.builder()
                    .pteid(pteid)
                    .settings(objectMapper.writeValueAsString(merged))
                    .build());
        } catch (Exception e) {
            log.warn("写入通知设置失败 pteid={} err={}", pteid, e.getMessage());
        }
        return merged;
    }

    private Map<String, Object> defaultSettings() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("redscreen", true);
        m.put("appeal", true);
        m.put("ticket", true);
        m.put("announcement", true);
        return m;
    }

    /** WS 推送：广播给全部在线，定向给目标玩家。 */
    private void push(Notification n) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("type", "notify");
        frame.put("notification_id", n.getId());
        frame.put("title", n.getTitle());
        frame.put("content", n.getContent());
        frame.put("priority", n.getPriority());
        frame.put("created_at", n.getCreatedAt() == null ? "" : n.getCreatedAt().toString());
        try {
            String payload = objectMapper.writeValueAsString(frame);
            if (n.getPteid() == null || n.getPteid().isBlank()) {
                onlineStatusService.broadcast(payload);
            } else {
                onlineStatusService.firstSession(n.getPteid())
                        .ifPresent(s -> onlineStatusService.broadcastTo(s, payload));
            }
        } catch (Exception e) {
            log.warn("推送通知失败 id={} err={}", n.getId(), e.getMessage());
        }
    }

    private java.util.Set<String> readIds(String pteid) {
        return notifReadRepository.findByPteid(pteid).stream()
                .map(PlayerNotifRead::getNotifId)
                .collect(Collectors.toSet());
    }
}