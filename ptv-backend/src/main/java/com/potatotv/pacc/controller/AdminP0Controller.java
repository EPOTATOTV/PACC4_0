package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.AdminRole;
import com.potatotv.pacc.domain.AlertEvent;
import com.potatotv.pacc.domain.AlertRule;
import com.potatotv.pacc.domain.Appeal;
import com.potatotv.pacc.domain.CheatRecord;
import com.potatotv.pacc.domain.DeviceRecord;
import com.potatotv.pacc.domain.InspectSession;
import com.potatotv.pacc.domain.RedscreenAlert;
import com.potatotv.pacc.domain.DetectionEvent;
import com.potatotv.pacc.repository.AccountRepository;
import com.potatotv.pacc.repository.AdminRoleRepository;
import com.potatotv.pacc.repository.AlertRuleRepository;
import com.potatotv.pacc.repository.AppealRepository;
import com.potatotv.pacc.repository.CheatRecordRepository;
import com.potatotv.pacc.repository.DeviceRecordRepository;
import com.potatotv.pacc.repository.InspectSessionRepository;
import com.potatotv.pacc.repository.RedscreenAlertRepository;
import com.potatotv.pacc.repository.DetectionEventRepository;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.AlertService;
import com.potatotv.pacc.service.OnlineStatusService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 管理端 P0：实时监控大屏 / 玩家详情 / 红屏详情 / 告警中心 / 角色权限。
 * <p>统计由既有持久化表派生；告警规则内置默认值、角色内置项在空表时由 @PostConstruct 播种，
 * 自定义规则 / 角色持久化到 t_alert_rule / t_admin_role。告警确认状态为瞬态，进程内承载。</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
@SuppressWarnings("null") // 流/存储层泛型 null 分析误报（本地定性安全）
public class AdminP0Controller {

    private final DetectionEventRepository eventRepository;
    private final RedscreenAlertRepository redscreenRepository;
    private final AccountRepository accountRepository;
    private final CheatRecordRepository recordRepository;
    private final AppealRepository appealRepository;
    private final InspectSessionRepository inspectRepository;
    private final DeviceRecordRepository deviceRepository;
    private final AlertRuleRepository alertRuleRepository;
    private final AdminRoleRepository roleRepository;
    private final OnlineStatusService onlineStatusService;
    private final AlertService alertService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** 已确认告警 id 集合（确认状态为瞬态，进程内承载即可）。 */
    private static final Set<String> ACKED_ALERTS = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void seedDefaults() {
        if (alertRuleRepository.count() == 0) {
            alertRuleRepository.saveAll(List.of(
                    alertRule("r1", "高红屏率", "REALTIME", "redscreen_rate", 3, 10, true, "dashboard,email"),
                    alertRule("r2", "检测风暴", "REALTIME", "detection_rate", 50, 5, true, "dashboard"),
                    alertRule("r3", "查端积压", "QUEUE", "pending_inspect", 20, 15, true, "email"),
                    alertRule("r4", "误报率超标", "SIGNATURE", "false_positive_rate", 2, 30, false, "dashboard")));
        }
        if (roleRepository.count() == 0) {
            roleRepository.saveAll(List.of(
                    adminRole("role-super", "超级管理员", "SUPER_ADMIN", "全部模块与操作", true, 1, toJson(grantAll(true))),
                    adminRole("role-operator", "运营", "OPERATOR", "检测与运营操作", true, 1, toJson(grantOperator())),
                    adminRole("role-analyst", "分析师", "ANALYST", "查端与情报分析", true, 1, toJson(grantAnalyst())),
                    adminRole("role-viewer", "只读", "VIEWER", "只读浏览", true, 1, toJson(grantAll(false)))));
        }
    }

    private static AlertRule alertRule(String id, String name, String scope, String condition,
                                       int threshold, int cooldown, boolean enabled, String channels) {
        return AlertRule.builder()
                .id(id).name(name).scope(scope).condition(condition)
                .threshold(threshold).cooldownMin(cooldown).enabled(enabled).channels(channels)
                .build();
    }

    private static AdminRole adminRole(String id, String name, String key, String desc,
                                       boolean builtin, int members, String permissions) {
        return AdminRole.builder()
                .id(id).name(name).roleKey(key).description(desc)
                .builtin(builtin).memberCount(members).permissions(permissions)
                .build();
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<Object> fromJson(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> splitChannels(String s) {
        if (s == null || s.isBlank()) return List.of();
        return Arrays.stream(s.split(",")).map(String::trim).filter(x -> !x.isEmpty()).collect(Collectors.toList());
    }

    // -------------------------------- 实时监控 --------------------------------

    @GetMapping("/realtime/overview")
    public Map<String, Object> realtimeOverview() {
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<DetectionEvent> today = eventRepository.findByOccurredAtAfter(dayStart.minusSeconds(1));
        long detections = today.size();
        long redsToday = redscreenRepository.countByOccurredAtBetween(dayStart, Instant.now());
        double avgRisk = today.stream().mapToInt(DetectionEvent::getClientRiskScore).average().orElse(0.0);
        long pending = inspectRepository.findAll().stream()
                .filter(s -> "QUEUED".equals(s.getState())).count();
        long active = inspectRepository.findAll().stream()
                .filter(s -> "ACTIVE".equals(s.getState())).count();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("online", onlineStatusService.onlineCount());
        out.put("detections", detections);
        out.put("redscreenToday", redsToday);
        out.put("pendingInspect", pending);
        out.put("activeInspect", active);
        out.put("avgRisk", Math.round(avgRisk * 10.0) / 10.0);
        return out;
    }

    @GetMapping("/realtime/runtime")
    public List<Map<String, Object>> realtimeRuntime() {
        Instant now = Instant.now();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(stat("accounts", "注册账号", accountRepository.count(), "", "ok"));
        out.add(stat("cheat_records", "作弊记录（未撤销）", recordRepository.countByRevokedFalse(), "", "ok"));
        out.add(stat("redscreen_total", "红屏累计", redscreenRepository.count(), "", "ok"));
        out.add(stat("pending_appeals", "待审申诉", appealRepository.countByStatus("pending"), "", "ok"));
        out.add(stat("pending_inspect", "待查验", inspectRepository.findAll().stream().filter(s -> "QUEUED".equals(s.getState())).count(), "", "warn"));
        out.add(stat("detect_30m", "近30分钟检测", eventRepository.countByOccurredAtBetween(now.minusSeconds(1800), now), "", "ok"));
        return out;
    }

    @GetMapping("/realtime/events")
    public List<Map<String, Object>> realtimeEvents(@RequestParam(defaultValue = "30") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DetectionEvent e : eventRepository.findTop50ByOccurredAtAfterOrderByOccurredAtDesc(Instant.EPOCH)) {
            if (out.size() >= Math.max(1, limit)) break;
            out.add(alertView(e.getId(), severityLevel(e.getSeverity()), e.getEventType() == null ? "detection" : e.getEventType(),
                    "玩家检测命中", e.getPteid(), e.getOccurredAt() == null ? "" : e.getOccurredAt().toString()));
        }
        return out;
    }

    @GetMapping("/realtime/alerts")
    public List<Map<String, Object>> realtimeAlerts(@RequestParam(defaultValue = "20") int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RedscreenAlert a : redscreenRepository.findByStateOrderByOccurredAtDesc("PENDING_INSPECT")) {
            if (out.size() >= Math.max(1, limit)) break;
            out.add(alertView(a.getAlertId(), a.getLevel(), a.getCheatType() == null ? "redscreen" : a.getCheatType(),
                    "红屏待查验", a.getPteid() == null ? a.getPteidMasked() : a.getPteid(),
                    a.getOccurredAt() == null ? "" : a.getOccurredAt().toString()));
        }
        return out;
    }

    // -------------------------------- 玩家详情 --------------------------------

    @GetMapping("/players/{pteid}")
    public ResponseEntity<?> playerDetail(@PathVariable String pteid) {
        Account acc = accountRepository.findById(pteid).orElse(null);
        if (acc == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pteid", acc.getPteid());
        out.put("email", acc.getEmail());
        out.put("reputation", acc.getReputation());
        out.put("status", acc.getStatus());
        out.put("registeredAt", acc.getRegisteredAt() == null ? "" : acc.getRegisteredAt().toString());
        out.put("totalRedscreen", acc.getTotalRedscreen());
        out.put("lastActiveAt", acc.getLastRedScreenTime() == null ? null : acc.getLastRedScreenTime().toString());
        List<Appeal> appeals = appealRepository.findByPteidOrderByCreatedAtDesc(pteid);
        List<DeviceRecord> devices = deviceRepository.findByPteidOrderByLastLoginAtDesc(pteid);
        List<InspectSession> inspects = inspectRepository.findAll().stream()
                .filter(s -> pteid.equals(s.getPteid()))
                .sorted(Comparator.comparing(InspectSession::getStartedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<CheatRecord> records = recordRepository.findByPteidOrderByOccurredAtDesc(pteid, PageRequest.of(0, 200)).getContent();
        out.put("devices", devices);
        out.put("appeals", appeals);
        out.put("inspects", inspects);
        out.put("records", records);
        return ResponseEntity.ok(out);
    }

    // -------------------------------- 红屏详情 --------------------------------

    @GetMapping("/redscreens/{id}")
    public ResponseEntity<?> redscreenAdminDetail(@PathVariable String id) {
        RedscreenAlert a = redscreenRepository.findById(id).orElse(null);
        if (a == null) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("eventId", a.getAlertId());
        out.put("triggeredAt", a.getOccurredAt() == null ? "" : a.getOccurredAt().toString());
        out.put("level", a.getLevel());
        out.put("state", a.getState());
        out.put("cheatType", a.getCheatType());
        out.put("riskScore", a.getRiskScore());
        out.put("edition", a.getEdition());
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
        Account acc = a.getPteid() == null ? null : accountRepository.findById(a.getPteid()).orElse(null);
        Map<String, Object> player = new LinkedHashMap<>();
        player.put("pteid", a.getPteid());
        player.put("email", acc == null ? null : acc.getEmail());
        player.put("reputation", acc == null ? 0 : acc.getReputation());
        player.put("status", acc == null ? null : acc.getStatus());
        out.put("player", player);
        return ResponseEntity.ok(out);
    }

    // -------------------------------- 告警中心 --------------------------------

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(required = false) Integer level,
                                            @RequestParam(required = false) String status) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RedscreenAlert a : redscreenRepository.findTop50ByOrderByOccurredAtDesc()) {
            if (level != null && a.getLevel() != level) continue;
            String st = a.getState();
            if (st == null || "PENDING_INSPECT".equals(st)) {
                st = ackd(a.getAlertId()) ? "acknowledged" : "open";
            } else {
                st = "resolved";
            }
            if (status != null && !status.isBlank() && !status.equals(st)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.getAlertId());
            m.put("level", a.getLevel());
            m.put("type", a.getCheatType());
            m.put("status", st);
            m.put("message", "红屏警告：" + (a.getCheatType() == null ? "—" : a.getCheatType()) + " L" + a.getLevel());
            m.put("player", a.getPteid() == null ? a.getPteidMasked() : a.getPteid());
            m.put("time", a.getOccurredAt() == null ? "" : a.getOccurredAt().toString());
            out.add(m);
        }
        return out;
    }

    @PostMapping("/alerts/{id}/ack")
    @RequirePermission("alerts:update")
    public ResponseEntity<?> ackAlert(@PathVariable String id) {
        ACKED_ALERTS.add(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/alerts/rules")
    public List<Map<String, Object>> alertRules() {
        return alertRuleRepository.findAllByOrderByIdAsc().stream().map(this::ruleView).toList();
    }

    private Map<String, Object> ruleView(AlertRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("scope", r.getScope());
        m.put("condition", r.getCondition());
        m.put("threshold", r.getThreshold());
        m.put("cooldownMin", r.getCooldownMin());
        m.put("enabled", r.isEnabled());
        m.put("channels", splitChannels(r.getChannels()));
        return m;
    }

    @PostMapping("/alerts/rules")
    public Map<String, Object> saveRule(@RequestBody Map<String, Object> body) {
        AlertRule saved = alertRuleRepository.save(AlertRule.builder()
                .id(UUID.randomUUID().toString())
                .name(String.valueOf(body.getOrDefault("name", "未命名规则")))
                .scope(String.valueOf(body.getOrDefault("scope", "REALTIME")))
                .condition(String.valueOf(body.getOrDefault("condition", "")))
                .threshold(num(body.get("threshold")))
                .cooldownMin(num(body.get("cooldownMin")))
                .enabled(Boolean.TRUE.equals(body.get("enabled")))
                .channels(joinChannels((List<?>) body.getOrDefault("channels", List.of("dashboard"))))
                .build());
        return ruleView(saved);
    }

    @PutMapping("/alerts/rules/{id}")
    public ResponseEntity<?> toggleRule(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AlertRule r = alertRuleRepository.findById(id).orElse(null);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        r.setEnabled(Boolean.TRUE.equals(body.get("enabled")));
        alertRuleRepository.save(r);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // -------------------------------- 告警事件（规则引擎） --------------------------------

    @GetMapping("/alerts/events")
    public List<Map<String, Object>> alertEvents(@RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "50") int limit) {
        return alertService.listEvents(status, limit).stream().map(this::eventView).toList();
    }

    @PostMapping("/alerts/events/{id}/ack")
    @RequirePermission("alerts:update")
    public ResponseEntity<?> ackAlertEvent(@PathVariable String id, HttpServletRequest request) {
        try {
            return ResponseEntity.ok(eventView(alertService.acknowledge(id, actorOf(request))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/alerts/events/{id}/resolve")
    @RequirePermission("alerts:update")
    public ResponseEntity<?> resolveAlertEvent(@PathVariable String id, @RequestBody(required = false) Map<String, Object> body,
                                               HttpServletRequest request) {
        try {
            String note = body == null ? null : String.valueOf(body.getOrDefault("note", ""));
            return ResponseEntity.ok(eventView(alertService.resolve(id, note, actorOf(request))));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/alerts/stats")
    public Map<String, Object> alertStats() {
        return alertService.stats();
    }

    private Map<String, Object> eventView(AlertEvent e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("ruleId", e.getRuleId());
        m.put("ruleName", e.getRuleName());
        m.put("severity", e.getSeverity());
        m.put("metric", e.getMetric());
        m.put("conditionValue", e.getConditionValue());
        m.put("threshold", e.getThreshold());
        m.put("actualValue", e.getActualValue());
        m.put("status", e.getStatus());
        m.put("firedAt", e.getFiredAt() == null ? "" : e.getFiredAt().toString());
        m.put("acknowledgedAt", e.getAcknowledgedAt() == null ? "" : e.getAcknowledgedAt().toString());
        m.put("acknowledgedBy", e.getAcknowledgedBy());
        m.put("resolvedAt", e.getResolvedAt() == null ? "" : e.getResolvedAt().toString());
        m.put("resolutionNote", e.getResolutionNote());
        return m;
    }

    /** 操作人：会话令牌身份或静态 Key 指纹（与 AdminKeyFilter 一致）。 */
    private static String actorOf(HttpServletRequest request) {
        Object actor = request.getAttribute("adminActor");
        return actor == null ? "api-key" : actor.toString();
    }

    // -------------------------------- 角色与权限 --------------------------------

    @GetMapping("/roles")
    public List<Map<String, Object>> roles() {
        return roleRepository.findAllByOrderByBuiltinDesc().stream().map(this::roleView).toList();
    }

    private Map<String, Object> roleView(AdminRole r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("name", r.getName());
        m.put("key", r.getRoleKey());
        m.put("description", r.getDescription());
        m.put("builtin", r.isBuiltin());
        m.put("memberCount", r.getMemberCount());
        m.put("permissions", fromJson(r.getPermissions()));
        return m;
    }

    @GetMapping("/roles/{id}")
    public ResponseEntity<?> role(@PathVariable String id) {
        AdminRole r = roleRepository.findById(id).orElse(null);
        return r == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(roleView(r));
    }

    @PostMapping("/roles")
    @RequirePermission("roles:create")
    public ResponseEntity<?> createRole(@RequestBody Map<String, Object> body) {
        String name = String.valueOf(body.getOrDefault("name", "新角色"));
        String key = String.valueOf(body.getOrDefault("key", "CUSTOM_" + name)).toUpperCase();
        if (roleRepository.findByRoleKey(key).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "角色标识已存在"));
        }
        AdminRole saved = roleRepository.save(AdminRole.builder()
                .id("role-" + UUID.randomUUID().toString())
                .name(name)
                .roleKey(key)
                .description(String.valueOf(body.getOrDefault("description", "")))
                .builtin(false)
                .memberCount(0)
                .permissions(toJson(body.getOrDefault("permissions", buildPermissionMatrix(false))))
                .build());
        return ResponseEntity.ok(roleView(saved));
    }

    @PutMapping("/roles/{id}")
    @RequirePermission("roles:update")
    public ResponseEntity<?> updateRole(@PathVariable String id, @RequestBody Map<String, Object> body) {
        AdminRole r = roleRepository.findById(id).orElse(null);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        if (body.containsKey("name")) r.setName(String.valueOf(body.get("name")));
        if (body.containsKey("description")) r.setDescription(String.valueOf(body.get("description")));
        if (body.containsKey("permissions")) r.setPermissions(toJson(fromPermissions(body.get("permissions"))));
        roleRepository.save(r);
        return ResponseEntity.ok(roleView(r));
    }

    @DeleteMapping("/roles/{id}")
    @RequirePermission("roles:delete")
    public ResponseEntity<?> deleteRole(@PathVariable String id) {
        AdminRole r = roleRepository.findById(id).orElse(null);
        if (r == null) {
            return ResponseEntity.notFound().build();
        }
        if (r.isBuiltin()) {
            return ResponseEntity.badRequest().body(Map.of("error", "内置角色不可删除"));
        }
        roleRepository.delete(r);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private Integer num(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        return o == null ? null : Integer.valueOf(String.valueOf(o));
    }

    private String joinChannels(List<?> channels) {
        if (channels == null || channels.isEmpty()) return "";
        return channels.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private List<Object> fromPermissions(Object o) {
        if (o instanceof String s) {
            return fromJson(s);
        }
        if (o instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return List.of();
    }

    private static List<Object> grantAll(boolean all) {
        List<Object> out = new ArrayList<>();
        for (String[] mod : MODULES) {
            Map<String, Object> perm = new LinkedHashMap<>();
            perm.put("module", mod[0]);
            perm.put("moduleLabel", mod[1]);
            List<Map<String, Object>> actions = new ArrayList<>();
            for (String[] act : ACTIONS) {
                Map<String, Object> action = new LinkedHashMap<>();
                action.put("key", act[0]);
                action.put("label", act[1]);
                action.put("granted", all);
                actions.add(action);
            }
            perm.put("actions", actions);
            out.add(perm);
        }
        return out;
    }

    private static List<Object> grantOperator() {
        List<Object> base = grantAll(true);
        for (Object o : base) {
            Map<String, Object> perm = (Map<String, Object>) o;
            if (List.of("roles", "system", "tenant").contains(perm.get("module"))) {
                for (Map<String, Object> a : (List<Map<String, Object>>) perm.get("actions")) {
                    a.put("granted", "read".equals(a.get("key")));
                }
            }
        }
        return base;
    }

    private static List<Object> grantAnalyst() {
        List<Object> base = grantAll(false);
        for (Object o : base) {
            Map<String, Object> perm = (Map<String, Object>) o;
            for (Map<String, Object> a : (List<Map<String, Object>>) perm.get("actions")) {
                a.put("granted", "read".equals(a.get("key"))
                        || List.of("inspect", "alerts", "competition").contains(perm.get("module")));
            }
        }
        return base;
    }

    private static final String[][] MODULES = {
            {"dashboard", "数据大盘"}, {"redscreen", "红屏管理"}, {"inspect", "远程查端"},
            {"accounts", "账号管理"}, {"players", "玩家详情"}, {"records", "作弊记录"},
            {"competition", "赛事风控"}, {"support", "客服工单"}, {"alerts", "告警中心"},
            {"roles", "角色权限"}, {"realtime", "实时监控"}, {"system", "系统管理"},
            {"bi", "BI 报表"}, {"tenant", "多租户"}, {"audit", "审计日志"},
    };

    private static final String[][] ACTIONS = {
            {"read", "查看"}, {"create", "新建"}, {"update", "编辑"}, {"delete", "删除"},
    };

    private static List<Object> buildPermissionMatrix(boolean granted) {
        List<Object> out = new ArrayList<>();
        for (String[] mod : MODULES) {
            List<Map<String, Object>> actions = new ArrayList<>();
            for (String[] act : ACTIONS) {
                actions.add(Map.of("key", act[0], "label", act[1], "granted", granted));
            }
            out.add(Map.of("module", mod[0], "moduleLabel", mod[1], "actions", actions));
        }
        return out;
    }

    // -------------------------------- 辅助 --------------------------------

    private static Map<String, Object> stat(String key, String label, Object value, String unit, String status) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("value", value);
        m.put("unit", unit);
        m.put("status", status);
        return m;
    }

    private static Map<String, Object> alertView(String id, int level, String type, String message,
                                                 String player, String time) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("level", level);
        m.put("type", type);
        m.put("message", message);
        m.put("player", player);
        m.put("time", time);
        return m;
    }

    private static int severityLevel(String severity) {
        if (severity == null) return 1;
        return switch (severity.toLowerCase()) {
            case "critical" -> 3;
            case "high" -> 2;
            case "medium" -> 1;
            default -> 0;
        };
    }

    private boolean ackd(String id) {
        return ACKED_ALERTS.contains(id);
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
}