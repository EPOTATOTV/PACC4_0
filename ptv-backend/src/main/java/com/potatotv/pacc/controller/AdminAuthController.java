package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.AdminLoginLog;
import com.potatotv.pacc.repository.AdminLoginLogRepository;
import com.potatotv.pacc.service.AdminTokenService;
import com.potatotv.pacc.service.FeishuAuthService;
import com.potatotv.pacc.service.LoginThrottle;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理后台登录：
 * <ul>
 *   <li>超级管理员授权模式：校验 X-Admin-Key / 密钥（支持超级管理员 Key 与普通运维 Key，分级不同角色）；</li>
 *   <li>公司飞书账号集成登录：OAuth 授权码换取身份并由 {@link AdminTokenService} 签发管理员会话令牌；</li>
 *   <li>登录日志：key / 飞书 两种方式的成功与失败均落库审计。</li>
 * </ul>
 * <p>含基于来源 IP 的频率限制与审计日志，日志不落任何密码/密钥/令牌明文。</p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthController.class);

    private final LoginThrottle throttle;
    private final AdminLoginLogRepository loginLogRepository;
    private final FeishuAuthService feishuAuthService;
    private final AdminTokenService adminTokenService;

    @Value("${pacc.security.admin-api-key}")
    private String operatorKey;

    @Value("${pacc.security.super-admin-key:}")
    private String superAdminKey;

    public AdminAuthController(LoginThrottle throttle,
                               AdminLoginLogRepository loginLogRepository,
                               FeishuAuthService feishuAuthService,
                               AdminTokenService adminTokenService) {
        this.throttle = throttle;
        this.loginLogRepository = loginLogRepository;
        this.feishuAuthService = feishuAuthService;
        this.adminTokenService = adminTokenService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request,
                                   HttpServletResponse response) {
        String ip = clientIp(request);
        String scope = "admin:" + ip;
        if (!throttle.allowed(scope)) {
            log.warn("管理后台登录被限流 ip={}", ip);
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请稍后再试"));
        }
        String key = body.get("admin_key");
        String role = null;
        // 优先超级管理员 Key，其次普通运维 Key（恒定时间比较，防时序侧信道）
        if (key != null && !superAdminKey.isBlank() && equal(superAdminKey, key)) {
            role = "super-admin";
        } else if (key != null && equal(operatorKey, key)) {
            role = "operator";
        }
        if (role == null) {
            throttle.hit(scope);
            record(ip, fingerprint(key), "key", null, "fail");
            log.warn("管理后台登录失败 ip={}", ip);
            return ResponseEntity.status(401).body(Map.of("error", "管理密钥错误"));
        }
        throttle.clear(scope);
        record(ip, fingerprint(key), "key", role, "success");
        log.info("管理后台登录成功 ip={} role={}", ip, role);
        // 会话令牌仅写入 HttpOnly cookie，响应体不暴露，防 XSS 窃取
        String token = adminTokenService.create("super|" + role, role, fingerprint(ip, request.getHeader("User-Agent")));
        setAdminCookie(response, token, SESSION_MAX_AGE);
        return ResponseEntity.ok(Map.of("ok", true, "role", role));
    }

    /** 飞书 OAuth：返回授权地址（供前端跳转）。 */
    @GetMapping("/feishu/oauth/url")
    public ResponseEntity<?> feishuUrl(HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getHeader("Host");
        String redirect = base + "/api/admin/feishu/oauth/callback";
        return ResponseEntity.ok(Map.of(
                "url", feishuAuthService.buildAuthorizeUrl(redirect),
                "enabled", feishuAuthService.isEnabled()));
    }

    /** 飞书 OAuth 回调：用授权码换取管理员身份，签发会话令牌。 */
    @PostMapping("/feishu/oauth/callback")
    public ResponseEntity<?> feishuCallback(@RequestBody Map<String, String> body, HttpServletRequest request,
                                            HttpServletResponse response) {
        String ip = clientIp(request);
        String scope = "feishu:" + ip;
        if (!throttle.allowed(scope)) {
            log.warn("飞书登录被限流 ip={}", ip);
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请稍后再试"));
        }
        String code = body.get("code");
        var user = feishuAuthService.exchange(code);
        if (user.isEmpty()) {
            throttle.hit(scope);
            record(ip, "(feishu)", "feishu", null, "fail");
            log.warn("飞书登录失败 ip={}", ip);
            return ResponseEntity.status(401).body(Map.of("error", "飞书授权验证失败"));
        }
        // 角色由 FeishuAuthService 依据配置白名单判定（默认拒绝，杜绝任意人员登录）
        String userId = user.get().userId();
        String role = user.get().role();
        throttle.clear(scope);
        record(ip, userId, "feishu", role, "success");
        log.info("飞书登录成功 ip={} userId={} role={}", ip, userId, role);
        String token = adminTokenService.create(userId, role, fingerprint(ip, request.getHeader("User-Agent")));
        setAdminCookie(response, token, SESSION_MAX_AGE);
        return ResponseEntity.ok(Map.of("ok", true, "role", role, "name", user.get().name()));
    }

    /** 管理端会话探测：cookie 有效时返回角色，供前端判定登录态。 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String token = cookieValue(request, ADMIN_COOKIE);
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        String role = adminTokenService.parseRoleWithFingerprint(token, fingerprint(request));
        if (role == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(Map.of("error", "会话失效，请重新登录"));
        }
        return ResponseEntity.ok(Map.of("ok", true, "role", role));
    }

    /** 管理端登出：清除 HttpOnly 会话 cookie。 */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        setAdminCookie(response, "", 0);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static final String ADMIN_COOKIE = "pacc_admin";
    /** 与 AdminTokenService 的 12h 会话有效期保持一致。 */
    private static final long SESSION_MAX_AGE = 12 * 60 * 60;

    /** 写入 HttpOnly 管理会话 cookie；Path=/ 使所有管理接口自动携带。 */
    private static void setAdminCookie(HttpServletResponse response, String value, long maxAgeSeconds) {
        StringBuilder sb = new StringBuilder(ADMIN_COOKIE).append('=').append(value)
                .append("; Path=/; HttpOnly; SameSite=Lax");
        if (maxAgeSeconds > 0) {
            sb.append("; Max-Age=").append(maxAgeSeconds);
        }
        // 生产 HTTPS 部署时附加 Secure；本地明文联调省略（与玩家 cookie 同一判定）
        if ("https".equalsIgnoreCase(System.getenv("PACC_HTTPS_DEPLOY"))
                || Boolean.parseBoolean(System.getenv("PACC_HTTPS_DEPLOY"))) {
            sb.append("; Secure");
        }
        response.addHeader("Set-Cookie", sb.toString());
    }

    private static String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (name.equals(c.getName())) return c.getValue();
        }
        return null;
    }

    /** 管理登录日志（审计）。 */
    @GetMapping("/login-logs")
    public ResponseEntity<?> loginLogs() {
        List<Map<String, Object>> rows = loginLogRepository.findTop50ByOrderByCreatedAtDesc().stream()
                .map(l -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", l.getId());
                    m.put("identity", l.getIdentity());
                    m.put("method", l.getMethod());
                    m.put("role", l.getRole());
                    m.put("result", l.getResult());
                    m.put("ip", l.getIp());
                    m.put("created_at", l.getCreatedAt());
                    return m;
                }).toList();
        return ResponseEntity.ok(Map.of("logs", rows));
    }

    private void record(String ip, String identity, String method, String role, String result) {
        try {
            loginLogRepository.save(AdminLoginLog.builder()
                    .identity(identity == null || identity.length() > 64 ? (identity == null ? "" : identity.substring(0, 64)) : identity)
                    .method(method).role(role).result(result).ip(ip)
                    .build());
        } catch (Exception e) {
            log.warn("写入管理登录日志失败: {}", e.getMessage());
        }
    }

    /** 密钥指纹：不落明文，仅存前 8 位 SHA-256。 */
    private String fingerprint(String key) {
        if (key == null || key.isEmpty()) return "";
        return sha256(key).substring(0, 8);
    }

    /** 来源指纹（IP + UA）：签发会话令牌时绑定，供后续每请求核验，防令牌跨设备冒用。 */
    static String fingerprint(String ip, String userAgent) {
        return sha256((ip == null ? "" : ip) + "|" + (userAgent == null ? "" : userAgent));
    }

    private static boolean equal(String expected, String provided) {
        return MessageDigest.isEqual(sha256(expected).getBytes(StandardCharsets.UTF_8),
                sha256(provided).getBytes(StandardCharsets.UTF_8));
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

    /** 客户端 IP：优先网关透传的 X-Forwarded-For 首段，回退连接地址。 */
    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}