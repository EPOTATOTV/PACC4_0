package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Enrollment;
import com.potatotv.pacc.domain.MapBanPickSession;
import com.potatotv.pacc.domain.MapEntry;
import com.potatotv.pacc.domain.MapPool;
import com.potatotv.pacc.repository.EnrollmentRepository;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.MapBanPickService;
import com.potatotv.pacc.service.MapService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 玩家端：地图池浏览与 BP 参与（/api/player/maps/**，JWT 保护）。
 * 仅 APPROVED 报名的选手可查看其所属的 BP 会话并进行 Ban/Pick；
 * 非参与者一律 403，避免信息泄露与越权操作。
 */
@RestController
@RequiredArgsConstructor
public class MapPlayerController {

    private final MapService mapService;
    private final MapBanPickService bpService;
    private final AccountService accountService;
    private final EnrollmentRepository enrollmentRepository;

    // ---------------- 地图池浏览 ----------------

    @GetMapping("/api/player/maps/pools")
    public List<MapPool> pools() {
        return mapService.activePools();
    }

    @GetMapping("/api/player/maps/pools/{poolId}")
    public ResponseEntity<?> pool(@PathVariable String poolId) {
        MapPool p = mapService.getPool(poolId);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    @GetMapping("/api/player/maps/pools/{poolId}/entries")
    public List<MapEntry> entries(@PathVariable String poolId) {
        return mapService.activeEntries(poolId);
    }

    // ---------------- BP 参与 ----------------

    /** 我当前进行中的 BP 会话。 */
    @GetMapping("/api/player/maps/bp/current")
    public ResponseEntity<?> currentBp(HttpServletRequest req) {
        return ResponseEntity.ok(bpService.activeForEnrollment(req.getAttribute("pteid").toString()));
    }

    /** 我参与过的 BP 历史（非 PENDING）。 */
    @GetMapping("/api/player/maps/bp/history")
    public ResponseEntity<?> history(HttpServletRequest req) {
        String pteid = req.getAttribute("pteid").toString();
        Enrollment en = enrollmentOf(pteid);
        List<MapBanPickSession> hist = bpService.all().stream()
                .filter(s -> isParticipant(en, s) && s.getStatus() != MapBanPickSession.Status.PENDING)
                .toList();
        return ResponseEntity.ok(hist);
    }

    /** BP 实时态（含当前回合/已 Ban/已 Pick/操作序列）。仅参与者可见。 */
    @GetMapping("/api/player/maps/bp/{bpId}")
    public ResponseEntity<?> bpState(@PathVariable String bpId, HttpServletRequest req) {
        if (!canView(bpId, req)) return forbidden();
        try {
            Map<String, Object> state = bpService.state(bpId);
            String side = mySide(req.getAttribute("pteid").toString(), bpId);
            if (side != null) state.put("my_side", side);
            return ResponseEntity.ok(state);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/api/player/maps/bp/{bpId}/actions")
    public ResponseEntity<?> bpActions(@PathVariable String bpId, HttpServletRequest req) {
        if (!canView(bpId, req)) return forbidden();
        return ResponseEntity.ok(bpService.actions(bpId));
    }

    /** 回合操作：body {action: BAN|PICK, map_id, device_fingerprint?}。 */
    @PostMapping("/api/player/maps/bp/{bpId}/action")
    public ResponseEntity<?> action(@PathVariable String bpId, @RequestBody Map<String, String> body,
                                    HttpServletRequest req) {
        String pteid = req.getAttribute("pteid").toString();
        String deviceFp = body.getOrDefault("device_fingerprint", currentFp(pteid));
        try {
            Map<String, Object> state = bpService.playerAction(bpId, body.get("action"),
                    body.get("map_id"), pteid, deviceFp, clientIp(req));
            return ResponseEntity.ok(state);
        } catch (SecurityException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ---------------- 权限辅助 ----------------

    private boolean canView(String bpId, HttpServletRequest req) {
        Enrollment en = enrollmentOf(req.getAttribute("pteid").toString());
        if (en == null || en.getStatus() != Enrollment.Status.APPROVED) return false;
        MapBanPickSession s = bpService.get(bpId);
        return s != null && isParticipant(en, s);
    }

    private static boolean isParticipant(Enrollment en, MapBanPickSession s) {
        if (en == null) return false;
        return en.getEnrollmentId().equals(s.getBlueEnrollmentId())
                || en.getEnrollmentId().equals(s.getRedEnrollmentId());
    }

    /** 当前登录选手在本 BP 中的位置（BLUE / RED），非参与者返回 null。 */
    private String mySide(String pteid, String bpId) {
        Enrollment en = enrollmentOf(pteid);
        MapBanPickSession s = bpService.get(bpId);
        if (en == null || s == null) return null;
        if (en.getEnrollmentId().equals(s.getBlueEnrollmentId())) return "BLUE";
        if (en.getEnrollmentId().equals(s.getRedEnrollmentId())) return "RED";
        return null;
    }

    private Enrollment enrollmentOf(String pteid) {
        return enrollmentRepository.findFirstByPteidOrderByCreatedAtDesc(pteid).orElse(null);
    }

    private String currentFp(String pteid) {
        return accountService.listDevices(pteid).stream()
                .filter(d -> d.isActive())
                .map(d -> d.getDeviceFingerprint())
                .findFirst()
                .orElse(null);
    }

    private String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String first = xff.split(",")[0].trim();
            if (!first.isBlank()) return first;
        }
        return req.getRemoteAddr();
    }

    private static ResponseEntity<?> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "无权访问该 BP 会话（非 APPROVED 参与者）"));
    }
}