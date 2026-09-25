package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.detection.v52.BehaviorProfileService;
import com.potatotv.pacc.service.detection.v52.ReputationV2Service;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
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

/**
 * v5.2 §6.2 / §7.2 行为画像与信誉端点。
 *
 * <p>管理端读操作与既有管理端点一致保持只读放行；写操作（人工调整信誉）标注
 * {@code players:update} 权限键（该键存在于 RBAC 目录：模块 {@code players} × 动作 {@code update}，
 * super-admin / api-key 直接放行）。</p>
 *
 * <p>玩家端复用既有玩家认证约定：{@code JwtAuthFilter} 解析会话后把 PTEID 写入请求属性
 * {@code pteid}（与 PlayerPortalController / PlayerP0Controller 一致），因此玩家只能读取自己的画像与信誉。</p>
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class V52ProfileController {

    private final BehaviorProfileService behaviorProfileService;
    private final ReputationV2Service reputationV2Service;

    // ------------------------------ 管理端 ------------------------------

    /** 玩家画像：在线统计、稳定度与当前自适应阈值倍率。 */
    @GetMapping("/admin/v52/profile/{pteid}")
    public ResponseEntity<?> profile(@PathVariable String pteid) {
        return ResponseEntity.ok(behaviorProfileService.profile(pteid));
    }

    /** 玩家信誉：0-1000 分值、等级、检测策略与最近审计明细。 */
    @GetMapping("/admin/v52/profile/{pteid}/reputation")
    public ResponseEntity<?> reputation(@PathVariable String pteid,
                                        @RequestParam(defaultValue = "20") int limit) {
        return ResponseEntity.ok(reputationV2Service.detail(pteid, limit));
    }

    /** 风险 / 高危玩家列表（信誉分 &lt; 500），按分值升序。 */
    @GetMapping("/admin/v52/profile/high-risk")
    public ResponseEntity<?> highRisk(@RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(Map.of("players", behaviorProfileService.highRisk(limit)));
    }

    /** 人工调整信誉分：必须填写原因，全程审计（来源事件为随机 id，不去重）。 */
    @PostMapping("/admin/v52/profile/{pteid}/reputation/adjust")
    @RequirePermission("players:update")
    public ResponseEntity<?> adjust(@PathVariable String pteid,
                                    @RequestBody Map<String, Object> body,
                                    HttpServletRequest req) {
        Object reasonValue = body.get("reason");
        String reason = reasonValue == null ? null : String.valueOf(reasonValue);
        Integer delta = asInt(body.get("delta"));
        if (delta == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "delta 必填且为整数"));
        }
        try {
            boolean applied = reputationV2Service.adjustManual(pteid, delta, reason, actor(req));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("applied", applied);
            out.put("reputation", reputationV2Service.detail(pteid, 5));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    // ------------------------------ 玩家端（复用玩家 JWT 会话约定） ------------------------------

    /** 我的画像：稳定度与自适应倍率（不暴露他人数据）。 */
    @GetMapping("/player/v52/profile")
    public ResponseEntity<?> myProfile(HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        return ResponseEntity.ok(behaviorProfileService.profile(pteid));
    }

    /** 我的信誉：0-1000 分值、等级、检测策略与最近审计明细。 */
    @GetMapping("/player/v52/profile/reputation")
    public ResponseEntity<?> myReputation(HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        return ResponseEntity.ok(reputationV2Service.detail(pteid, 20));
    }

    private static String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }

    private static String actor(HttpServletRequest req) {
        return req.getAttribute("adminActor") == null ? "api-key" : req.getAttribute("adminActor").toString();
    }

    private static Integer asInt(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}