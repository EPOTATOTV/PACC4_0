package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.detection.v52.ReplayStorageService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * v5.2 §7.3 查端回放端点。
 *
 * <p>玩家侧只上传（{@code octet-stream} 密文 + 头里的密钥/IV/元数据），服务端不解密；
 * 管理侧列元数据与下载解密后的 AVI，下载标注 {@code players:read} 权限键并记录访问日志
 * （录像属敏感取证材料，谁看过要能查）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class V52ReplayController {

    private final ReplayStorageService replayStorageService;

    /** 玩家上传录像：正文是 AES-256-GCM 密文。 */
    @PostMapping(value = "/player/v52/replay", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> upload(@RequestBody(required = false) byte[] cipher, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        try {
            Map<String, Object> saved = replayStorageService.store(pteid,
                    req.getHeader("X-PACC-Alert-Id"),
                    cipher,
                    req.getHeader("X-PACC-Replay-Key"),
                    req.getHeader("X-PACC-Replay-Iv"),
                    req.getHeader("X-PACC-Replay-Meta"),
                    req.getHeader("X-PACC-Replay-Sha256"));
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            log.warn("回放落盘失败 pteid={} err={}", pteid, e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "录像保存失败"));
        }
    }

    /** 管理端：录像元数据列表（可按 PTEID 过滤）。 */
    @GetMapping("/admin/v52/replay")
    @RequirePermission("players:read")
    public ResponseEntity<?> list(@RequestParam(required = false) String pteid,
                                  @RequestParam(defaultValue = "20") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", replayStorageService.list(pteid, limit));
        out.put("retention_days", ReplayStorageService.RETENTION_DAYS);
        return ResponseEntity.ok(out);
    }

    /** 管理端：解密下载（明文 AVI，仅授权管理员，访问记日志）。 */
    @GetMapping("/admin/v52/replay/{id}/download")
    @RequirePermission("players:read")
    public ResponseEntity<?> download(@PathVariable String id, HttpServletRequest req) {
        try {
            byte[] avi = replayStorageService.open(id);
            log.info("查端回放下载 id={} 操作者={} 明文={}B", id,
                    req.getAttribute("adminActor") == null ? "api-key" : req.getAttribute("adminActor"));
            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType("video/x-msvideo"))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"replay-" + id + ".avi\"")
                    .cacheControl(CacheControl.noStore())
                    .body(avi);
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    private static String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }
}