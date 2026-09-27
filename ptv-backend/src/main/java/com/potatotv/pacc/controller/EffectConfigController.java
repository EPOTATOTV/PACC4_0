package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.EffectConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 动效配置接口：
 * <ul>
 *   <li>/api/admin/effect —— 管理端读写（受 X-Admin-Key 保护）</li>
 *   <li>/api/dl/effect-config —— 客户端/下载站拉取下发视图</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/effect")
@RequiredArgsConstructor
public class EffectConfigController {

    private final EffectConfigService service;

    @GetMapping
    public Object get() {
        return service.current();
    }

    @PutMapping
    public ResponseEntity<?> update(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(service.update(body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 变更历史：默认最近 20 条。 */
    @GetMapping("/history")
    public Object history(@RequestParam(defaultValue = "20") int limit) {
        return service.history(limit);
    }
}