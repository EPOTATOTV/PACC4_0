package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.CompetitionService;
import com.potatotv.pacc.service.LoginThrottle;
import com.potatotv.pacc.service.TokenService;
import jakarta.servlet.http.HttpServletRequest;
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
    private final CompetitionService competitionService;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body, HttpServletRequest request) {
        try {
            Account a = accountService.register(body.get("email"), body.get("phone"),
                    body.get("password"), body.get("device_fingerprint"));
            TokenService.Token token = accountService.login(a.getPteid(), body.get("password"),
                    body.get("device_fingerprint"), true);
            competitionService.recordLogin(a.getPteid(), a.getDeviceFingerprint(), clientIp(request));
            log.info("玩家注册成功 pteid={}", a.getPteid());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pteid", a.getPteid());
            out.put("access_token", token.accessToken());
            out.put("expires_at", token.expiresAt());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            log.warn("玩家注册失败: {}", e.getMessage());
            // 仅模糊“已注册”等账号存在性信息，避免枚举；密码校验等提示保留以利用户体验
            boolean exposesExistence = e.getMessage() != null && e.getMessage().contains("已注册");
            String msg = exposesExistence ? "注册失败，请检查输入信息" : e.getMessage();
            return ResponseEntity.badRequest().body(Map.of("error", msg));
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String identity = body.get("identity");
        // 双维度限流：单账号（防单账号爆破）+ 单客户端 IP（防同一 IP 批量扫描账号）
        String ip = clientIp(request);
        String idScope = "auth:" + (identity == null ? "?" : identity);
        String ipScope = "ip:" + ip;
        if (!throttle.allowed(idScope) || !throttle.allowed(ipScope)) {
            log.warn("玩家登录被限流 identity={} ip={}", identity, ip);
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请稍后再试"));
        }
        try {
            boolean remember = Boolean.parseBoolean(body.getOrDefault("remember", "false"));
            TokenService.Token token = accountService.login(identity, body.get("password"),
                    body.get("device_fingerprint"), remember);
            throttle.clear(idScope);
            throttle.clear(ipScope);
            log.info("玩家登录成功 pteid={}", token.pteid());
            // 记账并触发代练/共享聚合检测
            Account acc = accountService.findByPteidOrNull(token.pteid());
            competitionService.recordLogin(token.pteid(),
                    acc == null ? null : acc.getDeviceFingerprint(), ip);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pteid", token.pteid());
            out.put("access_token", token.accessToken());
            out.put("expires_at", token.expiresAt());
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            throttle.hit(idScope);
            throttle.hit(ipScope);
            log.warn("玩家登录失败 ip={}: {}", ip, e.getMessage());
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        } catch (IllegalStateException e) {
            throttle.hit(idScope);
            throttle.hit(ipScope);
            log.warn("玩家登录被锁定 ip={}: {}", ip, e.getMessage());
            // 与“账号/密码错误”一致返回 401，不暴露锁定与账号状态（防枚举）
            return ResponseEntity.status(401).body(Map.of("error", "账号或密码错误"));
        }
    }

    /** 客户端 IP：优先网关透传的 X-Forwarded-For 首段，回退连接地址。 */
    private static String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}