package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.DetectorConfigService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * v5.0 P1：检测配置中心。读取目录（内置+自定义）、与检测试（list）与写入（upsert）。
 */
@RestController
@RequestMapping("/api/admin/detector-config")
@RequiredArgsConstructor
public class DetectorConfigController {

    private final DetectorConfigService detectorConfigService;

    @GetMapping
    public ResponseEntity<?> list() {
        return ResponseEntity.ok(Map.of(
                "detectors", detectorConfigService.list(),
                "catalog", detectorConfigService.catalog()));
    }

    @RequestMapping(method = RequestMethod.PUT)
    @RequirePermission("system:update")
    public ResponseEntity<?> upsert(@RequestBody Map<String, Object> body, HttpServletRequest req) {
        String operator = req.getAttribute("adminActor") == null
                ? "api-key" : req.getAttribute("adminActor").toString();
        try {
            boolean enabled = !(body.get("enabled") instanceof Boolean b && !b);
            return ResponseEntity.ok(detectorConfigService.upsert(
                    str(body.get("detector_key")),
                    str(body.get("name")),
                    enabled,
                    body.get("meta") == null ? null : String.valueOf(body.get("meta")),
                    operator));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}