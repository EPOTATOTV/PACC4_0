package com.potatotv.pacc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.service.security.AttestationService;
import com.potatotv.pacc.service.security.SecurityEventService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v5.4 §3.6 玩家端远程证明与安全事件上报。
 *
 * <p>与设计文档的一处取舍：文档设想「服务端主动发起挑战」，但端侧并非时刻在线（WSS 可能断连），
 * 服务端无法可靠推送。这里改为由客户端在需要证明时主动拉挑战，再立即应答；TTL 与最大时延约束
 * 同样能挡住「先录好应答再回放」，因为 nonce 是每次新发的一次性值。</p>
 *
 * <p>认证：玩家 JWT 过滤器把身份放进请求属性 {@code pteid}；缺失即 401，不进入业务逻辑。</p>
 */
@RestController
@RequestMapping("/api/player/attestation")
@RequiredArgsConstructor
public class AttestationPlayerController {

    private final AttestationService attestation;
    private final SecurityEventService securityEvents;
    private final ObjectMapper objectMapper;

    /** 拉取一个挑战（nonce 一次性，短时有效）。 */
    @PostMapping("/challenge")
    public ResponseEntity<?> challenge(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        String codeHash = str(body.get("code_hash"), body.get("codeHash"));
        if (codeHash.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "code_hash required"));
        }
        AttestationService.Challenge ch = attestation.issue(
                pteid,
                str(body.get("platform"), ""),
                str(body.get("client_version"), body.get("clientVersion")),
                codeHash,
                str(body.get("config_hash"), body.get("configHash")));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("challenge_id", ch.challengeId());
        out.put("nonce", ch.nonce());
        out.put("issued_at", ch.issuedAt().toString());
        out.put("expires_at", ch.expiresAt().toString());
        out.put("ttl_ms", ch.ttlMs());
        return ResponseEntity.ok(out);
    }

    /** 应答挑战；服务端独立核验后返回 PASS/FAIL 与失败原因。 */
    @PostMapping("/respond")
    public ResponseEntity<?> respond(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        AttestationService.Result r = attestation.respond(
                str(body.get("challenge_id"), body.get("challengeId")),
                str(body.get("nonce"), null),
                str(body.get("code_hash"), body.get("codeHash")),
                str(body.get("config_hash"), body.get("configHash")),
                str(body.get("runtime_state"), body.get("runtimeState")),
                str(body.get("signature"), body.get("sig")),
                lng(body.get("elapsed_ms"), body.get("elapsedMs"), 0L));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", r.status());
        out.put("reason", r.reason());
        out.put("elapsed_ms", r.elapsedMs());
        out.put("record_id", r.recordId());
        return ResponseEntity.ok(out);
    }

    /** 安全事件上报：写入哈希链审计日志。 */
    @PostMapping("/events")
    public ResponseEntity<?> events(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        List<SecurityEventService.EventInput> inputs = new ArrayList<>();
        Object raw = body.get("events");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                inputs.add(new SecurityEventService.EventInput(
                        str(m.get("type"), null),
                        str(m.get("level"), null),
                        str(m.get("detail"), null),
                        evidenceOf(m.get("evidence")),
                        instantOf(m.get("occurred_at") != null ? m.get("occurred_at") : m.get("occurredAt"))));
            }
        }
        int accepted = securityEvents.record(
                pteid,
                str(body.get("platform"), ""),
                str(body.get("client_version"), body.get("clientVersion")),
                inputs);
        return ResponseEntity.ok(Map.of("accepted", accepted));
    }

    /** evidence 允许直接给字符串或 JSON 对象；对象统一序列化成 JSON 串再交给服务层裁剪。 */
    private String evidenceOf(Object v) {
        if (v == null) return null;
        if (v instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(v);
        } catch (Exception e) {
            return v.toString();
        }
    }

    /** 接受 epoch 毫秒或 ISO-8601 字符串；无法解析返回 null（服务层按 now 兜底）。 */
    private static Instant instantOf(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return Instant.ofEpochMilli(n.longValue());
        String s = v.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Instant.parse(s);
        } catch (Exception ignored) {
            // 继续尝试数字串
        }
        try {
            return Instant.ofEpochMilli(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }

    private static String str(Object primary, Object fallback) {
        Object v = primary != null ? primary : fallback;
        return v == null ? "" : v.toString();
    }

    private static long lng(Object primary, Object fallback, long def) {
        Object v = primary != null ? primary : fallback;
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(v.toString());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}