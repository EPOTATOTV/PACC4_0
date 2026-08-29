package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.LoginThrottle;
import com.potatotv.pacc.service.TokenService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 玩家端账号接口（注册 / 登录）。返回 PTEID 与 JWT 访问令牌。
 * <p>登录含来源身份频率限制与审计日志（不落明文密码/令牌）。</p>
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AccountService accountService;
    private final LoginThrottle throttle;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        try {
            Account a = accountService.register(body.get("email"), body.get("phone"),
                    body.get("password"), body.get("device_fingerprint"));
            TokenService.Token token = accountService.login(a.getPteid(), body.get("password"),
                    body.get("device_fingerprint"), true);
            log.info("玩家注册成功 pteid={}", a.getPteid());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pteid", a.getPteid());
            out.put("access_token", token.accessToken());
            out.put("expires_at", token.expiresAt());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            log.warn("玩家注册失败: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String identity = body.get("identity");
        String scope = "auth:" + (identity == null ? "?" : identity);
        if (!throttle.allowed(scope)) {
            log.warn("玩家登录被限流");
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请稍后再试"));
        }
        try {
            boolean remember = Boolean.parseBoolean(body.getOrDefault("remember", "false"));
            TokenService.Token token = accountService.login(identity, body.get("password"),
                    body.get("device_fingerprint"), remember);
            throttle.clear(scope);
            log.info("玩家登录成功 pteid={}", token.pteid());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pteid", token.pteid());
            out.put("access_token", token.accessToken());
            out.put("expires_at", token.expiresAt());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            throttle.hit(scope);
            log.warn("玩家登录失败: {}", e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            throttle.hit(scope);
            log.warn("玩家登录被锁定: {}", e.getMessage());
            return ResponseEntity.status(423).body(Map.of("error", e.getMessage()));
        }
    }
}