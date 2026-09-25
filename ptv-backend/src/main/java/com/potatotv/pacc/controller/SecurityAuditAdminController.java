package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.KnownGoodHash;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.security.AttestationService;
import com.potatotv.pacc.service.security.KnownGoodHashService;
import com.potatotv.pacc.service.security.OperatorIdentity;
import com.potatotv.pacc.service.security.SecurityEventService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v5.4 §3.6 管理端安全审计只读视图 + 摘要注册表写操作。
 *
 * <p>读的一律标 {@code players:read}（与 v5.3 只读端点一致）；写注册表标 {@code system:update}，
 * 因为改它等于改远程证明的信任根。操作者统一经 {@link OperatorIdentity} 归一，落在摘要行的 created_by 上。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/security")
@RequiredArgsConstructor
public class SecurityAuditAdminController {

    private final SecurityEventService events;
    private final AttestationService attestation;
    private final KnownGoodHashService hashes;

    /** 安全事件概览：窗口内计数、类型/等级分布、哈希链状态、最近事件。 */
    @GetMapping("/overview")
    @RequirePermission("players:read")
    public ResponseEntity<?> overview(@RequestParam(defaultValue = "24") int hours) {
        return ResponseEntity.ok(events.overview(hours));
    }

    /** 安全事件列表（可按等级/类型/玩家过滤）。 */
    @GetMapping("/events")
    @RequirePermission("players:read")
    public ResponseEntity<?> events(@RequestParam(required = false) String level,
                                    @RequestParam(required = false) String type,
                                    @RequestParam(required = false) String pteid,
                                    @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(events.list(level, type, pteid, limit));
    }

    /** 哈希链完整性校验：返回 ok / checked / 断链位置。 */
    @GetMapping("/chain/verify")
    @RequirePermission("players:read")
    public ResponseEntity<?> chainVerify(@RequestParam(defaultValue = "500") int limit) {
        SecurityEventService.ChainVerification cv = events.verifyChain(limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", cv.ok());
        out.put("checked", cv.checked());
        out.put("broken_at_seq", cv.brokenAtSeq());
        out.put("detail", cv.detail());
        return ResponseEntity.ok(out);
    }

    /** 远程证明记录 + 通过率。 */
    @GetMapping("/attestation")
    @RequirePermission("players:read")
    public ResponseEntity<?> attestation(@RequestParam(required = false) String pteid,
                                         @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(attestation.list(pteid, limit));
    }

    /** 已知good摘要注册表。 */
    @GetMapping("/hashes")
    @RequirePermission("players:read")
    public ResponseEntity<?> hashes() {
        return ResponseEntity.ok(Map.of("items", hashes.list()));
    }

    /** 登记已知good摘要；重复登记返回既有行（幂等）。 */
    @PostMapping("/hashes")
    @RequirePermission("system:update")
    public ResponseEntity<?> addHash(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String actor = OperatorIdentity.of(req);
        try {
            KnownGoodHash row = hashes.add(
                    str(body.get("label")),
                    str(body.get("kind")),
                    str(body.get("hash")),
                    actor,
                    bool(body.get("active"), true));
            log.info("登记已知good摘要 id={} kind={} 操作者={}", row.getId(), row.getKind(), actor);
            return ResponseEntity.ok(hashes.viewOf(row));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 下线一条摘要（保留行做审计）。 */
    @PostMapping("/hashes/{id}/deactivate")
    @RequirePermission("system:update")
    public ResponseEntity<?> deactivateHash(@PathVariable String id, HttpServletRequest req) {
        try {
            hashes.deactivate(id, OperatorIdentity.of(req));
            return ResponseEntity.ok(Map.of("deactivated", id));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static boolean bool(Object v, boolean def) {
        if (v == null) return def;
        if (v instanceof Boolean b) return b;
        String s = v.toString().trim();
        if (s.isEmpty()) return def;
        return Boolean.parseBoolean(s);
    }
}