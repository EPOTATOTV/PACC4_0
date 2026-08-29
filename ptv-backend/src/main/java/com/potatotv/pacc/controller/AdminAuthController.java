package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * 管理后台登录：校验管理员 API Key。
 * <p>含登录审计日志与基于来源 IP 的频率限制（暴力破解缓解）。</p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final LoginThrottle throttle;

    @Value("${pacc.security.admin-api-key}")
    private String expectedKey;

    public AdminAuthController(LoginThrottle throttle) {
        this.throttle = throttle;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String scope = "admin:" + (request.getRemoteAddr() == null ? "?" : request.getRemoteAddr());
        if (!throttle.allowed(scope)) {
            log.warn("管理后台登录被限流 ip={}", request.getRemoteAddr());
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请稍后再试"));
        }
        String key = body.get("admin_key");
        boolean ok = key != null && MessageDigest.isEqual(
                sha256(expectedKey).getBytes(StandardCharsets.UTF_8),
                sha256(key).getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            throttle.hit(scope);
            log.warn("管理后台登录失败 ip={}", request.getRemoteAddr());
            return ResponseEntity.status(401).body(Map.of("error", "管理密钥错误"));
        }
        throttle.clear(scope);
        log.info("管理后台登录成功 ip={}", request.getRemoteAddr());
        return ResponseEntity.ok(Map.of("ok", true, "role", "ptv-operator"));
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}