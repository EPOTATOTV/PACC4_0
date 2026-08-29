package com.potatotv.pacc.controller;

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
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    @Value("${pacc.security.admin-api-key}")
    private String expectedKey;

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String key = body.get("admin_key");
        boolean ok = key != null && MessageDigest.isEqual(
                sha256(expectedKey).getBytes(StandardCharsets.UTF_8),
                sha256(key).getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            return ResponseEntity.status(401).body(Map.of("error", "管理密钥错误"));
        }
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