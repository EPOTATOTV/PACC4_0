package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.MapBanPickSession;
import com.potatotv.pacc.domain.MapEntry;
import com.potatotv.pacc.domain.MapPool;
import com.potatotv.pacc.service.MapBanPickService;
import com.potatotv.pacc.service.MapService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 管理端：地图 BP 管理（/api/admin/maps/**，X-Admin-Key 保护）。
 * 约定参赛双方案用报名 ID（blue_enrollment_id / red_enrollment_id）绑定，
 * 地图从指定地图池中按回合序列 Ban/Pick。
 */
@RestController
@RequiredArgsConstructor
public class MapController {

    private final MapService mapService;
    private final MapBanPickService bpService;

    // ---------------- 地图池 ----------------

    @GetMapping("/api/admin/maps/pools")
    public List<MapPool> pools() {
        return mapService.pools();
    }

    @PostMapping("/api/admin/maps/pools")
    public ResponseEntity<?> createPool(@RequestBody Map<String, String> body) {
        MapPool p = mapService.createPool(body.get("name"), body.get("tournament_id"),
                body.get("game_mode"), body.get("edition"), body.get("description"), body.get("created_by"));
        return ResponseEntity.ok(p);
    }

    @PutMapping("/api/admin/maps/pools/{poolId}")
    public ResponseEntity<?> updatePool(@PathVariable String poolId, @RequestBody Map<String, String> body) {
        Boolean active = body.containsKey("active") ? Boolean.parseBoolean(body.get("active")) : null;
        MapPool p = mapService.updatePool(poolId, body.get("name"), body.get("tournament_id"),
                body.get("game_mode"), body.get("edition"), body.get("description"), active);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    @DeleteMapping("/api/admin/maps/pools/{poolId}")
    public ResponseEntity<?> deletePool(@PathVariable String poolId) {
        return mapService.deletePool(poolId)
                ? ResponseEntity.ok(Map.of("pool_id", poolId, "ok", true))
                : ResponseEntity.notFound().build();
    }

    // ---------------- 地图条目 ----------------

    @GetMapping("/api/admin/maps/pools/{poolId}/entries")
    public List<MapEntry> entries(@PathVariable String poolId) {
        return mapService.entries(poolId);
    }

    @GetMapping("/api/admin/maps/pools/{poolId}/stats")
    public Map<String, Object> stats(@PathVariable String poolId) {
        return mapService.stats(poolId);
    }

    @PostMapping("/api/admin/maps/pools/{poolId}/entries")
    public ResponseEntity<?> createEntry(@PathVariable String poolId, @RequestBody Map<String, String> body) {
        MapEntry e = mapService.createEntry(poolId, body.get("name"), body.get("name_en"),
                body.get("map_type"), body.get("author"), body.get("version"), body.get("difficulty"),
                body.get("thumbnail_url"), body.get("preview_images"), body.get("description"),
                body.get("download_url"), d(body.get("win_rate_blue")), d(body.get("win_rate_red")),
                i(body.get("order_no")), body.get("created_by"));
        return ResponseEntity.ok(e);
    }

    /** 批量新增（前端从 CSV 模板解析后整包提交）。 */
    @PostMapping("/api/admin/maps/pools/{poolId}/entries/batch")
    public ResponseEntity<?> batchAdd(@PathVariable String poolId, @RequestBody Map<String, Object> body) {
        Object raw = body.get("rows");
        if (!(raw instanceof List)) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 rows 数组"));
        }
        List<?> rows = (List<?>) raw;
        @SuppressWarnings("unchecked")
        int n = mapService.batchAdd(poolId, (List<Map<String, Object>>) (List<?>) rows, (String) body.get("created_by"));
        return ResponseEntity.ok(Map.of("pool_id", poolId, "added", n));
    }

    @PutMapping("/api/admin/maps/entries/{mapId}")
    public ResponseEntity<?> updateEntry(@PathVariable String mapId, @RequestBody Map<String, String> body) {
        Double wb = body.containsKey("win_rate_blue") ? d(body.get("win_rate_blue")) : null;
        Double wr = body.containsKey("win_rate_red") ? d(body.get("win_rate_red")) : null;
        Integer orderNo = body.containsKey("order_no") ? i(body.get("order_no")) : null;
        Boolean active = body.containsKey("active") ? Boolean.parseBoolean(body.get("active")) : null;
        MapEntry e = mapService.updateEntry(mapId, body.get("name"), body.get("name_en"), body.get("map_type"),
                body.get("author"), body.get("version"), body.get("difficulty"), body.get("thumbnail_url"),
                body.get("preview_images"), body.get("description"), body.get("download_url"),
                wb, wr, orderNo, active);
        return e == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(e);
    }

    @DeleteMapping("/api/admin/maps/entries/{mapId}")
    public ResponseEntity<?> deleteEntry(@PathVariable String mapId) {
        return mapService.deleteEntry(mapId)
                ? ResponseEntity.ok(Map.of("map_id", mapId, "ok", true))
                : ResponseEntity.notFound().build();
    }

    @PostMapping("/api/admin/maps/entries/{mapId}/toggle")
    public ResponseEntity<?> toggleEntry(@PathVariable String mapId) {
        return mapService.toggleEntry(mapId)
                ? ResponseEntity.ok(Map.of("map_id", mapId, "ok", true))
                : ResponseEntity.notFound().build();
    }

    // ---------------- BP 会话 ----------------

    @GetMapping("/api/admin/maps/bp")
    public List<MapBanPickSession> bpSessions() {
        return bpService.all();
    }

    @PostMapping("/api/admin/maps/bp")
    public ResponseEntity<?> createBp(@RequestBody Map<String, String> body) {
        MapBanPickSession.Format format;
        try {
            format = MapBanPickSession.Format.valueOf(body.getOrDefault("format", "BO1"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "format 需为 BO1/BO3/BO5"));
        }
        if (body.get("pool_id") == null || body.get("blue_enrollment_id") == null
                || body.get("red_enrollment_id") == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "需要 pool_id 与双方报名 ID"));
        }
        try {
            MapBanPickSession s = bpService.create(format, body.get("pool_id"), body.get("tournament_id"),
                    body.get("match_id"), body.get("stage_id"), body.get("blue_enrollment_id"),
                    body.get("red_enrollment_id"), body.get("blue_team_name"), body.get("red_team_name"),
                    i(body.get("turn_timeout_seconds")), body.get("referee"), body.get("created_by"));
            return ResponseEntity.ok(s);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/admin/maps/bp/{bpId}")
    public ResponseEntity<?> bpDetail(@PathVariable String bpId) {
        try {
            return ResponseEntity.ok(bpService.state(bpId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/api/admin/maps/bp/{bpId}/actions")
    public ResponseEntity<?> bpActions(@PathVariable String bpId) {
        return ResponseEntity.ok(bpService.actions(bpId));
    }

    @PostMapping("/api/admin/maps/bp/{bpId}/start")
    public ResponseEntity<?> start(@PathVariable String bpId, @RequestBody(required = false) Map<String, String> body) {
        return lifecycle(bpId, () -> bpService.start(bpId, operator(body)));
    }

    @PostMapping("/api/admin/maps/bp/{bpId}/pause")
    public ResponseEntity<?> pause(@PathVariable String bpId, @RequestBody(required = false) Map<String, String> body) {
        return lifecycle(bpId, () -> bpService.pause(bpId, operator(body)));
    }

    @PostMapping("/api/admin/maps/bp/{bpId}/resume")
    public ResponseEntity<?> resume(@PathVariable String bpId, @RequestBody(required = false) Map<String, String> body) {
        return lifecycle(bpId, () -> bpService.resume(bpId, operator(body)));
    }

    @PostMapping("/api/admin/maps/bp/{bpId}/reset")
    public ResponseEntity<?> reset(@PathVariable String bpId, @RequestBody(required = false) Map<String, String> body) {
        return lifecycle(bpId, () -> bpService.reset(bpId, operator(body)));
    }

    @PostMapping("/api/admin/maps/bp/{bpId}/cancel")
    public ResponseEntity<?> cancel(@PathVariable String bpId, @RequestBody(required = false) Map<String, String> body) {
        String reason = body == null ? null : body.getOrDefault("reason", "裁判取消");
        return lifecycle(bpId, () -> bpService.cancel(bpId, reason, operator(body)));
    }

    @PostMapping("/api/admin/maps/bp/{bpId}/complete")
    public ResponseEntity<?> complete(@PathVariable String bpId, @RequestBody(required = false) Map<String, String> body) {
        return lifecycle(bpId, () -> bpService.complete(bpId, operator(body)));
    }

    /** 裁判强制操作：action=BAN/PICK，map_id 可空（为空则系统随机选图）。 */
    @PostMapping("/api/admin/maps/bp/{bpId}/force")
    public ResponseEntity<?> force(@PathVariable String bpId, @RequestBody Map<String, String> body) {
        try {
            return ResponseEntity.ok(bpService.forceAction(bpId,
                    body.getOrDefault("action", "PICK"), body.get("map_id"), operator(body)));
        } catch (IllegalArgumentException | SecurityException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<?> lifecycle(String bpId, java.util.function.Supplier<?> task) {
        try {
            return ResponseEntity.ok(task.get());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.notFound().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String operator(Map<String, String> body) {
        return body == null ? null : body.get("operator");
    }

    private static double d(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 0; }
    }

    private static int i(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }
}