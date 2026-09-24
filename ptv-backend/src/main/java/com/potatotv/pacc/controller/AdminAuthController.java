package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.AdminLoginLog;
import com.potatotv.pacc.domain.SecurityTotp;
import com.potatotv.pacc.repository.AdminLoginLogRepository;
import com.potatotv.pacc.repository.SecurityTotpRepository;
import com.potatotv.pacc.service.AdminTokenService;
import com.potatotv.pacc.service.FeishuAuthService;
import com.potatotv.pacc.service.LoginLockout;
import com.potatotv.pacc.service.LoginThrottle;
import com.potatotv.pacc.service.TotpService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
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
    private final LoginLockout lockout;
    private final AdminLoginLogRepository loginLogRepository;
    private final FeishuAuthService feishuAuthService;
    private final AdminTokenService adminTokenService;
    private final com.potatotv.pacc.config.PermissionInterceptor permissionInterceptor;
    private final TotpService totpService;
    private final SecurityTotpRepository totpRepository;

    @Value("${pacc.security.admin-api-key}")
    private String operatorKey;

    @Value("${pacc.security.super-admin-key:}")
    private String superAdminKey;

    /**
     * 是否要求所有管理员都必须绑定 2FA。开启前必须先把各管理员身份绑定完成，
     * 否则未绑定者会被挡在门外（届时只能用 PACC_SECURITY_ADMIN_2FA_REQUIRED=false 临时放开）。
     */
    @Value("${pacc.security.admin-2fa-required:false}")
    private boolean admin2faRequired;

    public AdminAuthController(LoginThrottle throttle,
                               LoginLockout lockout,
                               AdminLoginLogRepository loginLogRepository,
                               FeishuAuthService feishuAuthService,
                               AdminTokenService adminTokenService,
                               com.potatotv.pacc.config.PermissionInterceptor permissionInterceptor,
                               TotpService totpService,
                               SecurityTotpRepository totpRepository) {
        this.throttle = throttle;
        this.lockout = lockout;
        this.loginLogRepository = loginLogRepository;
        this.feishuAuthService = feishuAuthService;
        this.adminTokenService = adminTokenService;
        this.permissionInterceptor = permissionInterceptor;
        this.totpService = totpService;
        this.totpRepository = totpRepository;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request,
                                   HttpServletResponse response) {
        String ip = clientIp(request);
        String scope = "admin:" + ip;
        // 长窗锁定与短窗限流都按来源 IP 计：管理密钥是高价值静态凭据，必须压制慢速爆破
        if (lockout.isLocked(scope) || !throttle.allowed(scope)) {
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
            lockout.recordFailure(scope);
            lockout.applyProgressiveDelay(scope);
            record(ip, fingerprint(key), "key", null, "fail");
            log.warn("管理后台登录失败 ip={}", ip);
            return ResponseEntity.status(401).body(Map.of("error", "管理密钥错误"));
        }
        throttle.clear(scope);
        lockout.reset(scope);
        String identity = "super|" + role;
        // 第二步闸门：已绑定 2FA 则改发 pending 令牌，本步不下发会话 cookie
        ResponseEntity<?> gate = secondStepGate(ip, identity, role, "key");
        if (gate != null) return gate;
        record(ip, fingerprint(key), "key", role, "success");
        log.info("管理后台登录成功 ip={} role={}", ip, role);
        // 会话令牌仅写入 HttpOnly cookie，响应体不暴露，防 XSS 窃取
        String token = adminTokenService.create(identity, role, fingerprint(ip, request.getHeader("User-Agent")));
        setAdminCookie(response, token, SESSION_MAX_AGE);
        return ResponseEntity.ok(Map.of("ok", true, "role", role));
    }

    /** 飞书 OAuth：返回授权地址（供前端整页跳转）。 */
    @GetMapping("/feishu/oauth/url")
    public ResponseEntity<?> feishuUrl(HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getHeader("Host");
        String redirect = base + "/api/admin/feishu/oauth/callback";
        String state = java.util.UUID.randomUUID().toString();
        return ResponseEntity.ok(Map.of(
                "url", feishuAuthService.buildAuthorizeUrl(redirect, state),
                "enabled", feishuAuthService.isEnabled()));
    }

    /** 飞书 OAuth 浏览器回跳端点：校验 code+state，签发 cookie 后 302 跳回前端。 */
    @GetMapping("/feishu/oauth/callback")
    public void feishuCallbackGet(@RequestParam(required = false) String code,
                                  @RequestParam(required = false) String state,
                                  HttpServletRequest request, HttpServletResponse response) throws IOException {
        String ip = clientIp(request);
        String error;
        if (!feishuAuthService.validateState(state)) {
            error = "state_verification_failed";
            log.warn("飞书回调 state 校验失败 ip={}", ip);
        } else {
            var user = feishuAuthService.exchange(code);
            if (user.isEmpty()) {
                error = "oauth_failed";
                log.warn("飞书登录失败 ip={}", ip);
            } else {
                String userId = user.get().userId();
                String role = user.get().role();
                // 第二步闸门：浏览器回跳场景不能用 JSON 承载 pending，改写入短时 HttpOnly cookie，
                // 前端凭 ?twofa=1 弹出验证码输入框。令牌不进 URL，避免落到访问日志与 Referer。
                if (needsSecondStep(userId)) {
                    record(ip, userId, "feishu", role, "2fa_pending");
                    setAdmin2faCookie(response, totpService.issueAdminPending(userId, role));
                    response.sendRedirect(loginRedirect(request) + "?twofa=1");
                    return;
                }
                record(ip, userId, "feishu", role, "success");
                log.info("飞书登录成功 ip={} userId={} role={}", ip, userId, role);
                String token = adminTokenService.create(userId, role, fingerprint(ip, request.getHeader("User-Agent")));
                setAdminCookie(response, token, SESSION_MAX_AGE);
                response.sendRedirect(loginRedirect(request));
                return;
            }
        }
        record(ip, "(feishu)", "feishu", null, "fail");
        response.sendRedirect(loginRedirect(request) + "?feishu_error=" + error);
    }

    /** 飞书回调后回到前端登录页；若部署同域则回根路径，否则回登录页。 */
    private static String loginRedirect(HttpServletRequest request) {
        String base = request.getScheme() + "://" + request.getHeader("Host");
        String ctx = request.getContextPath() == null ? "" : request.getContextPath();
        return base + ctx + "/";
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
        ResponseEntity<?> gate = secondStepGate(ip, userId, role, "feishu");
        if (gate != null) return gate;
        record(ip, userId, "feishu", role, "success");
        log.info("飞书登录成功 ip={} userId={} role={}", ip, userId, role);
        String token = adminTokenService.create(userId, role, fingerprint(ip, request.getHeader("User-Agent")));
        setAdminCookie(response, token, SESSION_MAX_AGE);
        return ResponseEntity.ok(Map.of("ok", true, "role", role, "name", user.get().name()));
    }

    /** 管理端会话探测：cookie 有效时返回角色与权限集合，供前端判定登录态与按钮级 gating。 */
    @GetMapping("/me")
    public ResponseEntity<?> me(HttpServletRequest request) {
        String token = cookieValue(request, ADMIN_COOKIE);
        if (token == null || token.isBlank()) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        String role = adminTokenService.parseRoleWithFingerprint(token, fingerprint(clientIp(request), request.getHeader("User-Agent")));
        if (role == null) {
            return ResponseEntity.status(HttpServletResponse.SC_UNAUTHORIZED).body(Map.of("error", "会话失效，请重新登录"));
        }
        String adminId = adminTokenService.identityOf(token);
        return ResponseEntity.ok(Map.of("ok", true, "role", role, "admin_id", adminId == null ? "" : adminId,
                "permissions", permissionInterceptor.permissionSet(adminId, role)));
    }

    /** 管理端登出：清除 HttpOnly 会话 cookie。 */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        setAdminCookie(response, "", 0);
        setAdmin2faCookie(response, "");
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // -------------------------------- 管理员两步验证 --------------------------------

    /** 管理员 2FA 在 t_security_totp 中的存储键：加前缀与玩家 PTEID 隔离，避免同一主键空间被撞。 */
    private static String totpKey(String identity) {
        return "admin:" + identity;
    }

    /** 该管理员身份是否已绑定 2FA。 */
    private boolean needsSecondStep(String identity) {
        return totpService.enabled(totpKey(identity));
    }

    /**
     * 第一步登录通过后的第二步闸门。返回非 null 表示不应下发会话 cookie，调用方须直接返回该响应。
     * <p>已绑定 → 返回 pending 令牌；未绑定但配置要求强制 → 拒绝（提示先去绑定）。</p>
     */
    private ResponseEntity<?> secondStepGate(String ip, String identity, String role, String method) {
        if (needsSecondStep(identity)) {
            record(ip, identity, method, role, "2fa_pending");
            log.info("管理员需完成第二步验证 identity={} ip={}", identity, ip);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("twofa_required", true);
            out.put("pending", totpService.issueAdminPending(identity, role));
            return ResponseEntity.ok(out);
        }
        if (admin2faRequired) {
            record(ip, identity, method, role, "2fa_required");
            log.warn("管理员未绑定 2FA 被拒绝 identity={} ip={}", identity, ip);
            return ResponseEntity.status(403)
                    .body(Map.of("error", "本环境要求管理员启用两步验证，请先在已登录会话中完成绑定"));
        }
        return null;
    }

    /**
     * 管理员登录第二步：校验 pending 令牌 + TOTP（或一次性恢复码），通过后签发会话 cookie。
     * <p>pending 可来自请求体（Key 登录返回）或 HttpOnly cookie（飞书回跳写入）。</p>
     */
    @PostMapping("/login/2fa")
    public ResponseEntity<?> login2fa(@RequestBody Map<String, String> body, HttpServletRequest request,
                                      HttpServletResponse response) {
        String ip = clientIp(request);
        String scope = "admin-2fa:" + ip;
        if (lockout.isLocked(scope) || !throttle.allowed(scope)) {
            log.warn("管理员 2FA 校验被限流 ip={}", ip);
            return ResponseEntity.status(429).body(Map.of("error", "尝试过于频繁，请稍后再试"));
        }
        String pending = body.get("pending");
        if (pending == null || pending.isBlank()) {
            pending = cookieValue(request, ADMIN_2FA_COOKIE);
        }
        if (pending == null || pending.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "登录会话已失效，请重新登录"));
        }
        TotpService.AdminPending ap;
        try {
            ap = totpService.parseAdminPending(pending);
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
        if (!totpService.validate(totpKey(ap.identity()), body.get("code"))) {
            throttle.hit(scope);
            lockout.recordFailure(scope);
            lockout.applyProgressiveDelay(scope);
            record(ip, ap.identity(), "totp", null, "fail");
            log.warn("管理员 2FA 校验失败 identity={} ip={}", ap.identity(), ip);
            return ResponseEntity.status(401).body(Map.of("error", "验证码不正确或已失效"));
        }
        throttle.clear(scope);
        lockout.reset(scope);
        String token = adminTokenService.create(ap.identity(), ap.role(),
                fingerprint(ip, request.getHeader("User-Agent")));
        setAdminCookie(response, token, SESSION_MAX_AGE);
        setAdmin2faCookie(response, "");
        record(ip, ap.identity(), "totp", ap.role(), "success");
        log.info("管理员 2FA 校验通过 identity={} role={} ip={}", ap.identity(), ap.role(), ip);
        return ResponseEntity.ok(Map.of("ok", true, "role", ap.role() == null ? "" : ap.role()));
    }

    /** 绑定第一步：生成 TOTP 种子并返回 otpauth 链接（此时尚未启用，须再调 enable 验证一次）。 */
    @PostMapping("/2fa/setup")
    public ResponseEntity<?> twofaSetup(HttpServletRequest request) {
        String identity = (String) request.getAttribute("adminActor");
        if (identity == null || identity.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        String key = totpKey(identity);
        byte[] seed = new byte[20];
        new java.security.SecureRandom().nextBytes(seed);
        String b32 = base32Encode(seed);
        String otpauth = "otpauth://totp/PACC:" + identity + "?secret=" + b32 + "&issuer=PACC&period=30&digits=6";
        totpRepository.findById(key).ifPresentOrElse(x -> {
            x.setSecret(b32);
            x.setEnabled(false);
            x.setUpdatedAt(java.time.Instant.now());
            totpRepository.save(x);
        }, () -> totpRepository.save(SecurityTotp.builder()
                .pteid(key)
                .secret(b32)
                .enabled(false)
                .createdAt(java.time.Instant.now())
                .build()));
        log.info("管理员生成 TOTP 种子 identity={}", identity);
        return ResponseEntity.ok(Map.of("secret", b32, "otpauth", otpauth));
    }

    /** 绑定第二步：校验一次动态码后正式启用。 */
    @PostMapping("/2fa/enable")
    public ResponseEntity<?> twofaEnable(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String identity = (String) request.getAttribute("adminActor");
        if (identity == null || identity.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        String key = totpKey(identity);
        SecurityTotp rec = totpRepository.findById(key).orElse(null);
        if (rec == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先获取 TOTP 密钥"));
        }
        if (!totpService.isValidTotp(key, body.get("code"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "验证码不正确或已过期"));
        }
        rec.setEnabled(true);
        rec.setUpdatedAt(java.time.Instant.now());
        totpRepository.save(rec);
        log.info("管理员启用 2FA identity={}", identity);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 管理员 2FA 状态。 */
    @GetMapping("/2fa/status")
    public ResponseEntity<?> twofaStatus(HttpServletRequest request) {
        String identity = (String) request.getAttribute("adminActor");
        if (identity == null || identity.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        String key = totpKey(identity);
        return ResponseEntity.ok(Map.of(
                "enabled", totpService.enabled(key),
                "required", admin2faRequired,
                "recovery_ready", totpService.status(key).getOrDefault("recovery_ready", false)));
    }

    /** 生成一次性恢复码（明文仅此刻返回一次）。 */
    @PostMapping("/2fa/recovery")
    public ResponseEntity<?> twofaRecovery(HttpServletRequest request) {
        String identity = (String) request.getAttribute("adminActor");
        if (identity == null || identity.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        String key = totpKey(identity);
        if (!totpService.enabled(key)) {
            return ResponseEntity.badRequest().body(Map.of("error", "请先启用两步验证"));
        }
        return ResponseEntity.ok(Map.of("recovery_codes", totpService.generateRecoveryCodes(key)));
    }

    /** 解绑 2FA：须提供当前动态码或一次性恢复码。强制模式下禁止解绑，否则闸门会被绕过。 */
    @PostMapping("/2fa/disable")
    public ResponseEntity<?> twofaDisable(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String identity = (String) request.getAttribute("adminActor");
        if (identity == null || identity.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        if (admin2faRequired) {
            return ResponseEntity.status(403)
                    .body(Map.of("error", "本环境要求管理员必须启用两步验证，无法解绑"));
        }
        if (!totpService.disable(totpKey(identity), body.get("code"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "验证码不正确，无法解绑两步验证"));
        }
        log.warn("管理员解绑 2FA identity={}", identity);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 与 PlayerP0Controller 同一套 Base32 编码，保证 otpauth 链接可被通用验证器识别。 */
    private static String base32Encode(byte[] data) {
        final String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                sb.append(alphabet.charAt((buffer >>> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            sb.append(alphabet.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return sb.toString();
    }

    private static final String ADMIN_COOKIE = "pacc_admin";
    private static final String ADMIN_2FA_COOKIE = "pacc_admin_2fa";
    /** 与 AdminTokenService 的 12h 会话有效期保持一致。 */
    private static final long SESSION_MAX_AGE = 12 * 60 * 60;
    /** 与 TotpService 的 pending 有效期（5 分钟）一致。 */
    private static final long PENDING_MAX_AGE = 5 * 60;

    /** 写入 HttpOnly 管理会话 cookie；Path=/ 使所有管理接口自动携带。 */
    private static void setAdminCookie(HttpServletResponse response, String value, long maxAgeSeconds) {
        response.addHeader("Set-Cookie", adminCookieHeader(ADMIN_COOKIE, value, maxAgeSeconds));
    }

    /** 第二步 pending 令牌 cookie：仅在飞书浏览器回跳链路使用，验证通过后立即清空。 */
    private static void setAdmin2faCookie(HttpServletResponse response, String value) {
        long maxAge = value == null || value.isEmpty() ? 0 : PENDING_MAX_AGE;
        response.addHeader("Set-Cookie", adminCookieHeader(ADMIN_2FA_COOKIE, value == null ? "" : value, maxAge));
    }

    private static String adminCookieHeader(String name, String value, long maxAgeSeconds) {
        StringBuilder sb = new StringBuilder(name).append('=').append(value)
                .append("; Path=/; HttpOnly; SameSite=Lax");
        if (maxAgeSeconds > 0) {
            sb.append("; Max-Age=").append(maxAgeSeconds);
        }
        // 生产 HTTPS 部署时附加 Secure；本地明文联调省略（与玩家 cookie 同一判定）
        if ("https".equalsIgnoreCase(System.getenv("PACC_HTTPS_DEPLOY"))
                || Boolean.parseBoolean(System.getenv("PACC_HTTPS_DEPLOY"))) {
            sb.append("; Secure");
        }
        return sb.toString();
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
    public static String fingerprint(String ip, String userAgent) {
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