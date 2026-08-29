package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家端账号接口（注册 / 登录）。返回 PTEID 与 JWT 访问令牌。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AccountService accountService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        try {
            Account a = accountService.register(body.get("email"), body.get("phone"),
                    body.get("password"), body.get("device_fingerprint"));
            TokenService.Token token = accountService.login(a.getPteid(), body.get("password"),
                    body.get("device_fingerprint"), true);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pteid", a.getPteid());
            out.put("access_token", token.accessToken());
            out.put("expires_at", token.expiresAt());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        try {
            boolean remember = Boolean.parseBoolean(body.getOrDefault("remember", "false"));
            TokenService.Token token = accountService.login(body.get("identity"), body.get("password"),
                    body.get("device_fingerprint"), remember);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pteid", token.pteid());
            out.put("access_token", token.accessToken());
            out.put("expires_at", token.expiresAt());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(423).body(Map.of("error", e.getMessage()));
        }
    }
}