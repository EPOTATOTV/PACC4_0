package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.service.detection.v46.ZeroDayAnomalyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.6 玩家端异常特征上报（受玩家 JWT 保护）：客户端周期性上报行为特征，
 * 服务端执行零日分布外检测并回传置信等级，供端侧提升采样或触发观察。
 */
@RestController
@RequestMapping("/api/player/v46")
@RequiredArgsConstructor
public class V46PlayerController {

    private final ZeroDayAnomalyService zeroDayService;

    @PostMapping("/anomaly")
    public ResponseEntity<?> reportAnomaly(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String pteid = pteidOf(req);
        if (pteid.isEmpty()) {
            return ResponseEntity.status(401).body(Map.of("error", "需要登录"));
        }
        String edition = str(body.get("edition"));
        FeatureVector fv = featuresOf(body.get("features"));
        if (fv.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "features required"));

        Map<String, Object> r = zeroDayService.assess(pteid, edition.isEmpty() ? "JAVA" : edition, fv);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("confidence_tier", r.get("confidence_tier"));
        out.put("composite", r.get("composite"));
        out.put("iso_score", r.get("iso_score"));
        out.put("finding_id", r.get("finding_id"));
        return ResponseEntity.ok(out);
    }

    private static String pteidOf(HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        return v == null ? "" : v.toString();
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static FeatureVector featuresOf(Object o) {
        FeatureVector fv = new FeatureVector();
        if (o instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                Object v = e.getValue();
                if (v instanceof Number n) {
                    fv.set(String.valueOf(e.getKey()), n.doubleValue());
                } else if (v != null) {
                    try {
                        fv.set(String.valueOf(e.getKey()), Double.parseDouble(v.toString()));
                    } catch (NumberFormatException ignored) {
                        // 非数值特征跳过
                    }
                }
            }
        }
        return fv;
    }
}