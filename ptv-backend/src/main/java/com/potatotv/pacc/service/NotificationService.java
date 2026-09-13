package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.Notification;
import com.potatotv.pacc.domain.NotificationSetting;
import com.potatotv.pacc.domain.PlayerNotifRead;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.NotificationRepository;
import com.potatotv.pacc.repository.NotificationSettingRepository;
import com.potatotv.pacc.repository.PlayerNotifReadRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 统一通知服务：管理端公告/定向通知落库 + WS 实时推送 + 可选邮件渠道。
 * 与派生态通知（红屏/申诉/工单）并存，玩家端通知中心合并展示。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@SuppressWarnings("null") // 存储层返回值的 null 分析误报
public class NotificationService {

    /** 通知投递渠道。 */
    public enum Channel { IN_APP, EMAIL }

    /** 默认投递渠道（站内推送）。 */
    private static final Set<Channel> DEFAULT_CHANNELS = EnumSet.of(Channel.IN_APP);

    /** 通知类型对应的玩家设置开关键。 */
    private static final Map<String, String> TYPE_SETTING_KEY = Map.of(
            "REDSCREEN_ALERT", "redscreen",
            "APPEAL_RESULT", "appeal",
            "TICKET_REPLY", "ticket");

    private final NotificationRepository notificationRepository;
    private final NotificationSettingRepository settingRepository;
    private final PlayerNotifReadRepository notifReadRepository;
    private final OnlineStatusService onlineStatusService;
    private final AccountRepository accountRepository;
    private final ObjectProvider<MailerService> mailerProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 广播公告：落库并向在线玩家 WS 推送 notify 帧。 */
    @Transactional
    public Notification sendToAll(String title, String content, String scope, String type, String priority) {
        return sendToAll(title, content, scope, type, priority, DEFAULT_CHANNELS);
    }

    /** 广播公告，可指定额外渠道（邮件对广播无意义，仍以站内为主）。 */
    @Transactional
    public Notification sendToAll(String title, String content, String scope, String type, String priority, Set<Channel> channels) {
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
        push(n, channels);
        return n;
    }

    /** 定向通知：落库并仅推送给目标玩家在线会话 + 按其设置投递邮件。 */
    @Transactional
    public Notification sendToOne(String pteid, String title, String content, String scope, String type, String priority) {
        return sendToOne(pteid, title, content, scope, type, priority, DEFAULT_CHANNELS);
    }

    /** 定向通知，可指定额外渠道（站内 + 邮件）。 */
    @Transactional
    public Notification sendToOne(String pteid, String title, String content, String scope, String type, String priority, Set<Channel> channels) {
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
        push(n, channels);
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
        Map<String, Object> ch = new LinkedHashMap<>();
        ch.put("IN_APP", true);
        ch.put("EMAIL", false);
        m.put("channels", ch);
        return m;
    }

    /** WS 推送 + 邮件派发：广播给全部在线，定向给目标玩家。邮件仅在玩家开启 EMAIL 渠道且绑定邮箱时投递。 */
    private void push(Notification n, Set<Channel> channels) {
        Set<Channel> target = (channels == null || channels.isEmpty()) ? DEFAULT_CHANNELS : channels;
        if (target.contains(Channel.IN_APP)) {
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
        if (target.contains(Channel.EMAIL) && n.getPteid() != null && !n.getPteid().isBlank()) {
            dispatchEmail(n);
        }
    }

    /** 邮件派发：受玩家渠道开关约束，且需绑定邮箱。 */
    private void dispatchEmail(Notification n) {
        String pteid = n.getPteid();
        if (!playerEmailEnabled(pteid, n.getType())) {
            return;
        }
        Account acc = accountRepository.findById(pteid).orElse(null);
        if (acc == null || acc.getEmail() == null || acc.getEmail().isBlank()) {
            return;
        }
        MailerService mailer = mailerProvider.getIfAvailable();
        if (mailer == null) {
            log.debug("暂无 MailerService，跳过邮件渠道 id={}", n.getId());
            return;
        }
        mailer.send(acc.getEmail(), "[PACC] " + n.getTitle(), n.getContent());
    }

    /** 玩家是否允许该类型通知走邮件渠道。 */
    private boolean playerEmailEnabled(String pteid, String type) {
        try {
            Map<String, Object> settings = getSettings(pteid);
            String key = TYPE_SETTING_KEY.getOrDefault(type == null ? "" : type, "announcement");
            Object on = settings.get(key);
            if (on instanceof Boolean b && !b) {
                return false; // 该类型站内推送已关，邮件同步关闭
            }
            Object channels = settings.get("channels");
            if (!(channels instanceof Map<?, ?> cm)) {
                return false;
            }
            Object email = cm.get("EMAIL");
            return email instanceof Boolean eb && eb;
        } catch (Exception e) {
            return false;
        }
    }

    private java.util.Set<String> readIds(String pteid) {
        return notifReadRepository.findByPteid(pteid).stream()
                .map(PlayerNotifRead::getNotifId)
                .collect(Collectors.toSet());
    }
}