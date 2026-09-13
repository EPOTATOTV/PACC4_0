package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.Broadcast;
import com.potatotv.pacc.domain.DeviceRecord;
import com.potatotv.pacc.domain.LoginEvent;
import com.potatotv.pacc.domain.PlayerNotifRead;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.domain.SecurityTotp;
import com.potatotv.pacc.domain.SupportTicket;
import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.domain.TicketMessage;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.BroadcastRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.repository.DeviceRecordRepository;
import com.potatotv.pacc.repository.LoginEventRepository;
import com.potatotv.pacc.repository.PlayerNotifReadRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import com.potatotv.pacc.repository.SecurityTotpRepository;
import com.potatotv.pacc.repository.SupportTicketRepository;
import com.potatotv.pacc.repository.TicketMessageRepository;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.NotificationService;
import com.potatotv.pacc.service.TotpService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 玩家端 P0：实时保护 / 检测监控 / 红屏详情 / 通知中心 / 账号安全 / 申诉与工单对话。
 * <p>受 JwtAuthFilter 保护（/api/player 前缀）。数据由既有持久化表派生；
 * TOTP 密钥 / 工单对话 / 通知已读分别持久化到 t_security_totp / t_ticket_message / t_player_notif_read。</p>
 */
@RestController
@RequestMapping("/api/player")
@RequiredArgsConstructor
@SuppressWarnings("null") // 流/存储层泛型 null 分析误报（本地定性安全）
public class PlayerP0Controller {

    private final DetectionEventRepository eventRepository;
    private final RedscreenAlertRepository redscreenRepository;
    private final AccountRepository accountRepository;
    private final DeviceRecordRepository deviceRepository;
    private final LoginEventRepository loginEventRepository;
    private final AppealRepository appealRepository;
    private final SupportTicketRepository ticketRepository;
    private final SecurityTotpRepository totpRepository;
    private final TicketMessageRepository messageRepository;
    private final PlayerNotifReadRepository notifReadRepository;
    private final AccountService accountService;
    private final BroadcastRepository broadcastRepository;
    private final NotificationService notificationService;
    private final TotpService totpService;

    private String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }

    // -------------------------------- 赛事直播转播（只读） --------------------------------

    @GetMapping("/stream-live")
    public List<Map<String, Object>> streamLive(HttpServletRequest req) {
        pteidOf(req); // 触发 JwtAuthFilter 已注入身份，保持鉴权上下文
        return broadcastRepository.findByLiveTrueOrderBySortAscCreatedAtDesc().stream()
                .map(this::broadcastToView)
                .collect(Collectors.toList());
    }

    private Map<String, Object> broadcastToView(Broadcast b) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", b.getId());
        m.put("title", b.getTitle());
        m.put("bilibili_live_id", b.getBilibiliLiveId());
        m.put("cover_url", b.getCoverUrl());
        m.put("description", b.getDescription());
        m.put("platform", b.getPlatform());
        return m;
    }

    // -------------------------------- 实时保护 --------------------------------

    @GetMapping("/protection/status")
    public Map<String, Object> protectionStatus(HttpServletRequest req) {
        String pteid = pteidOf(req);
        List<DetectionEvent> mine = eventsOf(pteid);
        List<RedscreenAlert> reds = redsOf(pteid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("running", true);
        out.put("state", "RUNNING");
        out.put("mode", "AGGRESSIVE");
        out.put("scannable_regions", mine.isEmpty() ? 0 : mine.stream().map(DetectionEvent::getEventType).distinct().count());
        out.put("scanned_regions", mine.size());
        out.put("uptime_sec", 0);
        out.put("detection_count", mine.size());
        out.put("redscreen_count", reds.size());
        out.put("last_event_type", mine.stream()
                .max(Comparator.comparing(DetectionEvent::getOccurredAt))
                .map(DetectionEvent::getEventType).orElse(null));
        return out;
    }

    @GetMapping("/protection/resources")
    public List<Map<String, Object>> protectionResources(HttpServletRequest req) {
        // 端侧 CPU/内存/网络遥测暂未持久化，返回空态；接入遥测表后自动出数据。
        return List.of();
    }

    @GetMapping("/protection/stats")
    public Map<String, Object> protectionStats(HttpServletRequest req) {
        String pteid = pteidOf(req);
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<DetectionEvent> mine = eventsOf(pteid);
        long det = mine.stream().filter(e -> !e.getOccurredAt().isBefore(dayStart)).count();
        long high = mine.stream().filter(e -> !e.getOccurredAt().isBefore(dayStart))
                .filter(e -> "high".equalsIgnoreCase(e.getSeverity()) || "critical".equalsIgnoreCase(e.getSeverity())).count();
        long reds = redsOf(pteid).stream().filter(a -> !a.getOccurredAt().isBefore(dayStart)).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("detections_today", det);
        out.put("high_risk_today", high);
        out.put("redscreen_today", reds);
        out.put("false_positive_today", 0L);
        return out;
    }

    @GetMapping("/protection/events")
    public List<Map<String, Object>> protectionEvents(@RequestParam(defaultValue = "10") int limit, HttpServletRequest req) {
        String pteid = pteidOf(req);
        return eventsOf(pteid).stream()
                .sorted(Comparator.comparing(DetectionEvent::getOccurredAt).reversed())
                .limit(Math.max(1, Math.min(limit, 200)))
                .map(e -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", e.getId());
                    m.put("type", e.getEventType());
                    m.put("riskScore", e.getClientRiskScore());
                    m.put("timestamp", e.getOccurredAt().toString());
                    m.put("player", e.getPteid());
                    return m;
                })
                .toList();
    }

    @PostMapping("/protection/pause")
    public Map<String, Object> protectionPause() {
        return Map.of("running", false);
    }

    @PostMapping("/protection/resume")
    public Map<String, Object> protectionResume() {
        return Map.of("running", true);
    }

    @PostMapping("/protection/scan")
    public Map<String, Object> protectionScan() {
        return Map.of("ok", true);
    }

    // -------------------------------- 检测监控 --------------------------------

    @GetMapping("/monitor/detectors")
    public List<Map<String, Object>> monitorDetectors(HttpServletRequest req) {
        String pteid = pteidOf(req);
        Map<String, Long> byType = eventsOf(pteid).stream()
                .collect(Collectors.groupingBy(e -> e.getEventType() == null ? "unknown" : e.getEventType(), Collectors.counting()));
        String[][] catalog = {
                {"memory_tamper", "暴力 · 内存篡改", "violent"},
                {"process_injection", "暴力 · 进程注入", "violent"},
                {"auto_clicker", "暴力 · 自动点击", "violent"},
                {"input_bot", "暴力 · 输入外挂", "violent"},
                {"java_mod", "暴力 · Java 模组", "violent"},
                {"dma", "暴力 · DMA 直读", "violent"},
                {"stealth_kit", "隐身 · 隐匿工具", "stealth"},
                {"coroutine", "隐身 · 协程干扰", "stealth"},
                {"virtualize", "隐身 · 虚拟化", "stealth"},
        };
        List<Map<String, Object>> out = new ArrayList<>();
        for (String[] d : catalog) {
            long scan = byType.getOrDefault(d[0], 0L);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", d[0]);
            m.put("name", d[1]);
            m.put("kind", d[2]);
            m.put("state", scan > 0 ? "OK" : "IDLE");
            m.put("scanCount", scan);
            m.put("hitCount", scan);
            out.add(m);
        }
        return out;
    }

    @GetMapping("/monitor/ai")
    public Map<String, Object> monitorAi(HttpServletRequest req) {
        String pteid = pteidOf(req);
        List<DetectionEvent> mine = eventsOf(pteid);
        Map<String, Long> byType = mine.stream()
                .collect(Collectors.groupingBy(e -> e.getEventType() == null ? "unknown" : e.getEventType(), Collectors.counting()));
        List<Map<String, Object>> predictions = new ArrayList<>();
        byType.forEach((label, c) -> predictions.add(Map.of("label", label, "count", c)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelVersion", "pacc-ai-4.2");
        out.put("latencyMs", 0);
        out.put("predictions", predictions);
        return out;
    }

    @GetMapping("/monitor/logs")
    public List<Map<String, Object>> monitorLogs(@RequestParam(defaultValue = "100") int limit,
                                                 @RequestParam(required = false) String level,
                                                 @RequestParam(required = false) String origin,
                                                 @RequestParam(required = false) String q, HttpServletRequest req) {
        // 端侧运行日志未持久化，返回空态；接入遥测日志表后按 level/origin/q 过滤出数据。
        return List.of();
    }

    // -------------------------------- 红屏详情 --------------------------------

    @GetMapping("/redscreen/{id}")
    public ResponseEntity<?> redscreenDetail(@PathVariable String id, HttpServletRequest req) {
        String pteid = pteidOf(req);
        RedscreenAlert a = redscreenRepository.findById(id).orElse(null);
        if (a == null) {
            return ResponseEntity.notFound().build();
        }
        if (!pteid.isEmpty() && !a.getPteid().equals(pteid)) {
            return ResponseEntity.status(403).body(Map.of("error", "无权查看该事件"));
        }
        return ResponseEntity.ok(buildDetail(a));
    }

    // -------------------------------- 通知中心 --------------------------------

    @GetMapping("/notifications")
    public List<Map<String, Object>> notifications(@RequestParam(required = false) String kind, HttpServletRequest req) {
        String pteid = pteidOf(req);
        List<Map<String, Object>> all = new ArrayList<>();
        Instant now = Instant.now();
        for (RedscreenAlert a : redsOf(pteid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", "rs-" + a.getAlertId());
            n.put("kind", "redscreen");
            n.put("title", "检测到作弊行为（" + a.getCheatType() + "）");
            n.put("body", "风险评分 " + a.getRiskScore() + "，等级 L" + a.getLevel());
            n.put("read", ilRead(pteid, "rs-" + a.getAlertId()));
            n.put("createdAt", a.getOccurredAt() == null ? now.toString() : a.getOccurredAt().toString());
            all.add(n);
        }
        for (Appeal ap : appealRepository.findByPteidOrderByCreatedAtDesc(pteid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", "ap-" + ap.getAppealId());
            n.put("kind", "appeal");
            n.put("title", "申诉 " + ap.getAppealId().substring(0, Math.min(8, ap.getAppealId().length())) + " " + ap.getStatus());
            n.put("body", ap.getReviewComment());
            n.put("read", ilRead(pteid, "ap-" + ap.getAppealId()));
            n.put("createdAt", ap.getCreatedAt() == null ? now.toString() : ap.getCreatedAt().toString());
            all.add(n);
        }
        for (SupportTicket t : ticketRepository.findByPteidOrderByCreatedAtDesc(pteid)) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", "tk-" + t.getId());
            n.put("kind", "ticket");
            n.put("title", "工单「" + t.getTitle() + "」" + t.getStatus());
            n.put("body", t.getDescription());
            n.put("read", ilRead(pteid, "tk-" + t.getId()));
            n.put("createdAt", t.getUpdatedAt() == null ? now.toString() : t.getUpdatedAt().toString());
            all.add(n);
        }
        List<Map<String, Object>> sorted = all.stream()
                .filter(m -> kind == null || kind.isBlank() || kind.equals(m.get("kind")))
                .sorted(Comparator.comparing(m -> String.valueOf(m.get("createdAt")), Comparator.reverseOrder()))
                .toList();
        // 追加统一通知主表公告（广播 + 定向），与派生态并存
        List<Map<String, Object>> merged = new ArrayList<>(sorted);
        merged.addAll(notificationService.list(pteid, kind));
        merged.sort(Comparator.comparing(m -> String.valueOf(m.get("createdAt")), Comparator.reverseOrder()));
        return merged;
    }

    @GetMapping("/notifications/unread-count")
    public Map<String, Object> unreadCount(HttpServletRequest req) {
        String pteid = pteidOf(req);
        long unread = notifications(null, req).stream().filter(m -> !Boolean.TRUE.equals(m.get("read"))).count();
        return Map.of("count", unread);
    }

    @PostMapping("/notifications/{id}/read")
    public Map<String, Object> readNotif(@PathVariable String id, HttpServletRequest req) {
        String pteid = pteidOf(req);
        saveRead(pteid, id);
        return Map.of("ok", true);
    }

    @PostMapping("/notifications/read-all")
    public Map<String, Object> readAllNotifs(HttpServletRequest req) {
        String pteid = pteidOf(req);
        notifications(null, req).stream().map(m -> String.valueOf(m.get("id"))).forEach(id -> saveRead(pteid, id));
        return Map.of("ok", true);
    }

    @DeleteMapping("/notifications/{id}")
    public ResponseEntity<?> removeNotif(@PathVariable String id, HttpServletRequest req) {
        // 通知由事件派生、不物理删除；记录已读以兼容前端交互。
        saveRead(pteidOf(req), id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private void saveRead(String pteid, String notifId) {
        if (pteid.isEmpty() || notifId == null || notifId.isBlank()) return;
        if (readIds(pteid).contains(notifId)) return;
        notifReadRepository.save(PlayerNotifRead.builder()
                .id(UUID.randomUUID().toString())
                .pteid(pteid)
                .notifId(notifId)
                .readAt(Instant.now())
                .build());
    }

    private Set<String> readIds(String pteid) {
        return notifReadRepository.findByPteid(pteid).stream()
                .map(PlayerNotifRead::getNotifId)
                .collect(Collectors.toSet());
    }

    private boolean ilRead(String pteid, String id) {
        return readIds(pteid).contains(id);
    }

    // -------------------------------- 账号安全 --------------------------------

    @GetMapping("/security/score")
    public Map<String, Object> securityScore(HttpServletRequest req) {
        String pteid = pteidOf(req);
        Account acc = accountRepository.findById(pteid).orElse(null);
        int rep = acc == null ? 100 : acc.getReputation();
        long deviceCount = deviceRepository.findByPteidOrderByLastLoginAtDesc(pteid).size();
        int score = Math.max(0, Math.min(100, (int) Math.round(rep * 0.8 + (deviceCount <= 2 ? 20 : 5))));
        String level = score >= 80 ? "HIGH" : score >= 50 ? "MEDIUM" : "LOW";
        List<String> suggests = new ArrayList<>();
        if (deviceCount > 2) suggests.add("检测到多台登录设备，建议下线不常用设备");
        if (acc != null && "high_risk".equals(acc.getStatus())) suggests.add("账号存在高风险标记，请排查异常登录");
        if (totpRepository.findById(pteid).isEmpty()) suggests.add("建议开启两步验证（TOTP）以增强安全性");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("score", score);
        out.put("level", level);
        out.put("suggestions", suggests);
        return out;
    }

    @GetMapping("/security/devices")
    public List<Map<String, Object>> securityDevices(HttpServletRequest req) {
        String pteid = pteidOf(req);
        List<LoginEvent> logins = loginEventRepository.findByPteid(pteid);
        List<DeviceRecord> devs = deviceRepository.findByPteidOrderByLastLoginAtDesc(pteid);
        List<Map<String, Object>> out = new ArrayList<>();
        for (DeviceRecord d : devs) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", d.getDeviceId());
            m.put("name", d.getDeviceName() == null ? "绑定设备" : d.getDeviceName());
            m.put("platform", d.getPlatform());
            m.put("ip", d.getIp());
            m.put("online", d.isActive());
            m.put("suspicious", false);
            m.put("lastActiveAt", d.getLastLoginAt() == null ? "" : d.getLastLoginAt().toString());
            out.add(m);
        }
        for (LoginEvent le : logins) {
            String fp = le.getDeviceFingerprint() == null ? "" : le.getDeviceFingerprint();
            if (fp.isBlank()) continue;
            boolean known = devs.stream().anyMatch(d -> fp.equals(d.getDeviceFingerprint()));
            if (known) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("deviceId", "lg-" + le.getId());
            m.put("name", "历史登录 · " + shortFp(fp));
            m.put("platform", null);
            m.put("ip", le.getIp());
            m.put("online", false);
            m.put("suspicious", false);
            m.put("lastActiveAt", le.getCreatedAt() == null ? "" : le.getCreatedAt().toString());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/security/devices/{deviceId}/logout")
    public ResponseEntity<?> logoutDevice(@PathVariable String deviceId, HttpServletRequest req) {
        String pteid = pteidOf(req);
        deviceRepository.findById(deviceId).ifPresent(d -> {
            if (pteid.equals(d.getPteid())) {
                d.setActive(false);
                deviceRepository.save(d);
            }
        });
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/security/password")
    public ResponseEntity<?> changePassword(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        try {
            accountService.changePassword(pteid, body.get("old_password"), body.get("new_password"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/security/totp/setup")
    public Map<String, Object> totpSetup(HttpServletRequest req) {
        String pteid = pteidOf(req);
        byte[] secret = new byte[20];
        new SecureRandom().nextBytes(secret);
        String b32 = base32Encode(secret);
        String email = accountRepository.findById(pteid).map(Account::getEmail).orElse("player@" + pteid);
        String otpauth = "otpauth://totp/PACC:" + email + "?secret=" + b32 + "&issuer=PACC&period=30&digits=6";
        totpRepository.findById(pteid).ifPresentOrElse(x -> {
            x.setSecret(b32);
            x.setEnabled(false);
            x.setUpdatedAt(Instant.now());
            totpRepository.save(x);
        }, () -> totpRepository.save(SecurityTotp.builder()
                .pteid(pteid)
                .secret(b32)
                .enabled(false)
                .createdAt(Instant.now())
                .build()));
        return Map.of("secret", b32, "otpauth", otpauth);
    }

    @PostMapping("/security/totp/enable")
    public ResponseEntity<?> totpEnable(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        String code = body.get("code");
        SecurityTotp rec = totpRepository.findById(pteid).orElse(null);
        if (rec == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先获取 TOTP 密钥"));
        }
        if (!verifyTotp(rec.getSecret(), code)) {
            return ResponseEntity.badRequest().body(Map.of("error", "验证码不正确或已过期"));
        }
        rec.setEnabled(true);
        rec.setUpdatedAt(Instant.now());
        totpRepository.save(rec);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 2FA 状态（安全中心只读展示）。 */
    @GetMapping("/security/totp/status")
    public Map<String, Object> totpStatus(HttpServletRequest req) {
        return totpService.status(pteidOf(req));
    }

    /** 生成一次性恢复码（明文仅此刻返回一次，务必立即保存）。 */
    @PostMapping("/security/totp/recovery")
    public ResponseEntity<?> totpRecovery(HttpServletRequest req) {
        String pteid = pteidOf(req);
        SecurityTotp rec = totpRepository.findById(pteid).orElse(null);
        if (rec == null || !rec.isEnabled()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先启用两步验证"));
        }
        return ResponseEntity.ok(Map.of("recovery_codes", totpService.generateRecoveryCodes(pteid)));
    }

    /** 禁用 2FA：须提供当前 TOTP 或一次性恢复码解锁。 */
    @PostMapping("/security/totp/disable")
    public ResponseEntity<?> totpDisable(@RequestBody Map<String, String> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        boolean ok = totpService.disable(pteid, body.get("code"));
        if (!ok) {
            return ResponseEntity.badRequest().body(Map.of("error", "验证码不正确，无法禁用两步验证"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // -------------------------------- 申诉 / 工单 --------------------------------

    @GetMapping("/appeals/{id}")
    public ResponseEntity<?> appealDetail(@PathVariable String id, HttpServletRequest req) {
        String pteid = pteidOf(req);
        Appeal ap = appealRepository.findById(id).orElse(null);
        if (ap == null || !pteid.equals(ap.getPteid())) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("appealId", ap.getAppealId());
        out.put("pteid", ap.getPteid());
        out.put("alertId", ap.getAlertId());
        out.put("reason", ap.getReason());
        out.put("description", ap.getDescription());
        out.put("status", ap.getStatus());
        out.put("reviewer", ap.getReviewer());
        out.put("reviewComment", ap.getReviewComment());
        out.put("createdAt", ap.getCreatedAt() == null ? "" : ap.getCreatedAt().toString());
        out.put("reviewedAt", ap.getReviewedAt() == null ? "" : ap.getReviewedAt().toString());
        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(tl(ap.getCreatedAt(), "提交申诉"));
        if (ap.getStatus() != null && !"pending".equals(ap.getStatus()) && ap.getReviewedAt() != null) {
            timeline.add(tl(ap.getReviewedAt(), "审核完成：" + ap.getStatus(), ap.getReviewComment()));
        }
        out.put("timeline", timeline);
        out.put("messages", List.of());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/tickets/{id}/messages")
    public ResponseEntity<?> ticketMessages(@PathVariable String id, HttpServletRequest req) {
        String pteid = pteidOf(req);
        SupportTicket t = ticketRepository.findById(id)
                .filter(x -> pteid.equals(x.getPteid())).orElse(null);
        if (t == null) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> msgs = messageRepository.findByTicketIdOrderByCreatedAtAsc(id).stream()
                .map(m -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("id", m.getId());
                    map.put("reply", m.getContent());
                    map.put("responder", m.getResponder());
                    map.put("createdAt", m.getCreatedAt() == null ? "" : m.getCreatedAt().toString());
                    return map;
                })
                .toList();
        return ResponseEntity.ok(msgs);
    }

    @PostMapping("/tickets/{id}/reply")
    public ResponseEntity<?> ticketReply(@PathVariable String id, @RequestBody Map<String, String> body,
                                         HttpServletRequest req) {
        String pteid = pteidOf(req);
        SupportTicket t = ticketRepository.findById(id)
                .filter(x -> pteid.equals(x.getPteid())).orElse(null);
        if (t == null) {
            return ResponseEntity.notFound().build();
        }
        String reply = body.get("reply");
        if (reply == null || reply.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "reply 不能为空"));
        }
        TicketMessage saved = messageRepository.save(TicketMessage.builder()
                .id(UUID.randomUUID().toString())
                .ticketId(id)
                .responder("player")
                .content(reply)
                .createdAt(Instant.now())
                .build());
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", saved.getId());
        msg.put("reply", saved.getContent());
        msg.put("responder", saved.getResponder());
        msg.put("createdAt", saved.getCreatedAt() == null ? "" : saved.getCreatedAt().toString());
        return ResponseEntity.ok(msg);
    }

    // -------------------------------- 辅助 --------------------------------

    private List<DetectionEvent> eventsOf(String pteid) {
        if (pteid.isEmpty()) return List.of();
        return eventRepository.findAll().stream()
                .filter(e -> pteid.equals(e.getPteid())).toList();
    }

    private List<RedscreenAlert> redsOf(String pteid) {
        if (pteid.isEmpty()) return List.of();
        return redscreenRepository.findAll().stream()
                .filter(a -> pteid.equals(a.getPteid())).toList();
    }

    /** 构建红屏详情（证据缺省时返回空证据区，前端表现为空态）。 */
    private Map<String, Object> buildDetail(RedscreenAlert a) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", a.getAlertId());
        out.put("triggeredAt", a.getOccurredAt() == null ? "" : a.getOccurredAt().toString());
        out.put("level", a.getLevel());
        out.put("state", a.getState());
        out.put("cheatType", a.getCheatType());
        out.put("riskScore", a.getRiskScore());
        List<Map<String, Object>> hits = new ArrayList<>();
        Map<String, Object> hit = new LinkedHashMap<>();
        hit.put("name", a.getCheatType());
        hit.put("matched", true);
        hit.put("risk", a.getRiskScore());
        hits.add(hit);
        out.put("hitDetectors", hits);
        out.put("evidence", Map.of());
        List<Map<String, Object>> timeline = new ArrayList<>();
        timeline.add(tl(a.getOccurredAt(), "触发红屏"));
        if (a.getState() != null && !"PENDING_INSPECT".equals(a.getState()) && a.getResolvedAt() != null) {
            timeline.add(tl(a.getResolvedAt(), "处理结束：" + a.getState(), a.getInspectConclusion()));
        }
        out.put("timeline", timeline);
        Map<String, Object> player = new LinkedHashMap<>();
        Account acc = a.getPteid() == null ? null : accountRepository.findById(a.getPteid()).orElse(null);
        player.put("pteid", a.getPteid());
        player.put("reputation", acc == null ? 0 : acc.getReputation());
        player.put("device", acc == null ? "-" : (acc.getDeviceFingerprint() == null ? "-" : "绑定设备"));
        out.put("player", player);
        return out;
    }

    private static Map<String, Object> tl(Instant at, String action) {
        return tl(at, action, null);
    }

    private static Map<String, Object> tl(Instant at, String action, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("at", at == null ? "" : at.toString());
        m.put("action", action);
        m.put("note", note);
        return m;
    }

    private static String shortFp(String fp) {
        return fp.length() > 8 ? fp.substring(0, 8) + "…" : fp;
    }

    // -------------------------------- Base32 / TOTP --------------------------------

    private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private static String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32[(buffer >>> (bits - 5)) & 31]);
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(BASE32[(buffer << (5 - bits)) & 31]);
        }
        return sb.toString();
    }

    private static byte[] base32Decode(String s) {
        String clean = s.replace("=", "").toUpperCase();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char c : clean.toCharArray()) {
            int v = indexOfBase32(c);
            if (v < 0) continue;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >>> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private static int indexOfBase32(char c) {
        for (int i = 0; i < BASE32.length; i++) {
            if (BASE32[i] == c) return i;
        }
        return -1;
    }

    static boolean verifyTotp(String base32Secret, String code) {
        if (code == null || code.isBlank()) return false;
        try {
            byte[] key = base32Decode(base32Secret);
            long epoch = Instant.now().getEpochSecond();
            for (long window = -1; window <= 1; window++) {
                if (totp(key, epoch + window * 30L).equals(code)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static String totp(byte[] key, long time) throws Exception {
        long counter = time / 30L;
        byte[] data = new byte[8];
        for (int i = 7; i >= 0; i--) {
            data[i] = (byte) (counter & 0xff);
            counter >>>= 8;
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(data);
        int otp = hotp(hash, 6);
        return String.format("%06d", otp);
    }

    private static int hotp(byte[] hash, int digits) {
        int offset = hash[hash.length - 1] & 0x0f;
        int bin = ((hash[offset] & 0x7f) << 24)
                | ((hash[offset + 1] & 0xff) << 16)
                | ((hash[offset + 2] & 0xff) << 8)
                | (hash[offset + 3] & 0xff);
        int mod = 1;
        for (int i = 0; i < digits; i++) mod *= 10;
        return bin % mod;
    }
}