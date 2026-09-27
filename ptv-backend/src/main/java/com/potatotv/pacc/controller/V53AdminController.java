package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.v53.AdminInsightService;
import com.potatotv.pacc.service.v53.ReplayFrameService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/**
 * v5.3 管理端补齐的只读聚合端点：硬件指纹管理（§2.5）、行为趋势（§2.2）、信誉全量分布（§2.3）、
 * 回放逐帧在线预览（§2.4）。
 *
 * <p>定位：v5.2 的接口与语义一律不动，这里只做「已有数据的只读拼装」，因此全部是 GET、全部标注
 * {@code players:read}，写操作仍然只走 v5.2 既有端点。权限与认证由 {@code AdminKeyFilter} +
 * {@code PermissionInterceptor} 统一覆盖（{@code /api/admin/**}）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/v53")
@RequiredArgsConstructor
public class V53AdminController {

    private final AdminInsightService insights;
    private final ReplayFrameService frames;

    /** 硬件指纹明细（可按 PTEID 过滤，只留多账号共用的指纹）。 */
    @GetMapping("/devices")
    @RequirePermission("players:read")
    public ResponseEntity<?> devices(@RequestParam(required = false) String pteid,
                                     @RequestParam(defaultValue = "false") boolean sharedOnly) {
        return ResponseEntity.ok(insights.deviceFingerprints(pteid == null ? "" : pteid.trim(), sharedOnly));
    }

    /** 行为趋势：逐日在线时长 / 事件量 + 当前画像基线。 */
    @GetMapping("/profile/{pteid}/trend")
    @RequirePermission("players:read")
    public ResponseEntity<?> trend(@PathVariable String pteid,
                                   @RequestParam(defaultValue = "30") int days) {
        if (pteid.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "pteid 不能为空"));
        }
        return ResponseEntity.ok(insights.behaviorTrend(pteid, days));
    }

    /** 信誉全量分布 + 各等级检测策略 + 计分规则（只读，配置改动仍然只能改后端常量）。 */
    @GetMapping("/reputation/overview")
    @RequirePermission("players:read")
    public ResponseEntity<?> reputationOverview() {
        return ResponseEntity.ok(insights.reputationOverview());
    }

    /**
     * 回放逐帧预览：返回带操作者水印的 JPEG。
     * 前端按 fps 连续拉帧即可播放，磁盘上始终只有密文，密钥不下发。
     */
    @GetMapping("/replay/{id}/frame")
    @RequirePermission("players:read")
    public ResponseEntity<?> frame(@PathVariable String id,
                                   @RequestParam(defaultValue = "0") int index,
                                   HttpServletRequest req) {
        String actor = actorOf(req);
        try {
            ReplayFrameService.Frame frame = frames.frame(id, index, actor);
            log.info("查端回放预览 id={} 帧={}/{} 操作者={}", id, frame.index(), frame.total(), actor);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .header("X-PACC-Frame-Index", String.valueOf(frame.index()))
                    .header("X-PACC-Frame-Total", String.valueOf(frame.total()))
                    .cacheControl(CacheControl.noStore())
                    .body(frame.jpeg());
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(Map.of("error", e.getMessage()));
        }
    }

    private static String actorOf(HttpServletRequest req) {
        Object v = req.getAttribute("adminActor");
        return v == null ? "api-key" : v.toString();
    }
}