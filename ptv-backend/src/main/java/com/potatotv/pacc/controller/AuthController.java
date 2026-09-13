package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.CompetitionService;
import com.potatotv.pacc.service.LoginThrottle;
import com.potatotv.pacc.service.TokenService;
import com.potatotv.pacc.service.TotpService;
import com.potatotv.pacc.service.VerifyCodeService;
import com.potatotv.pacc.service.sms.SmsProvider;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
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
    private final VerifyCodeService verifyCodeService;
    private final SmsProvider smsProvider;
    private final TotpService totpService;

    /** 演示级邮件发送 stub：为 true 时在响应返回本地重置链接（生产必须关闭）。 */
    @Value("${pacc.mail.stub-enabled:true}")
    private boolean mailStub;

    @Value("${pacc.mail.reset-base-url:}")
    private String resetBaseUrl;

    @Value("${spring.mail.properties.mail.smtp.from:}")
    private String mailSmtpFrom;

    /** 回退发件人：SMTP 登录账号（多数服务商强制发件人=认证账号）。 */
    @Value("${spring.mail.username:}")
    private String mailUsername;

    @Autowired
    private JavaMailSender mailSender;

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body, HttpServletRequest request,
                                      HttpServletResponse response) {
        try {
            // 注册前必须持有发送到登记邮箱的验证码（校验并消费原码）
            String email = body.get("email");
            String code = body.get("code");
            if (email == null || email.isBlank() || code == null || code.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("error", "请输入邮箱与邮箱验证码"));
            }
            if (!verifyCodeService.verify(email, "register", code)) {
                return ResponseEntity.badRequest().body(Map.of("error", "验证码不正确或已过期，请重新获取"));
            }
            Account a = accountService.register(body.get("email"), body.get("phone"),
                    body.get("mcid"), body.get("ecid"), body.get("qq"),
                    body.get("netease_uuid"), body.get("password"),
                    body.get("device_fingerprint"));
            TokenService.Token token = accountService.login(a.getPteid(), body.get("password"),
                    body.get("device_fingerprint"), true);
            competitionService.recordLogin(a.getPteid(), a.getDeviceFingerprint(), clientIp(request));
            log.info("玩家注册成功 pteid={}", a.getPteid());
            setPlayerCookie(response, token.accessToken(), token.expiresAt());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("pteid", a.getPteid());
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

    @PostMapping("/forget")
    public ResponseEntity<?> forget(@RequestBody Map<String, String> body) {
        // 幂等下不暴露账号是否存在：无论是否命中一律返回相同提示
        String email = body.get("email");
        String token = accountService.createPasswordResetToken(email);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", "如果该邮箱已注册，重置链接将发送至邮箱");
        if (mailStub) {
            // 本地联调 stub：邮箱命中时返回可用的重置链接
            if (token != null) {
                out.put("dev_link", "/portal/forget?token=" + token);
                log.info("[邮件stub] 密码重置链接(仅演示)：{}", token);
            }
        } else if (token != null && mailSender != null) {
            // 真实发送：仅当邮箱存在且已配置 SMTP 时才发送，未命中不暴露
            sendResetMail(email, token);
        }
        return ResponseEntity.ok(out);
    }

    private void sendResetMail(String email, String token) {
        String base = (resetBaseUrl == null || resetBaseUrl.isBlank())
                ? "https://pacc.potatotv.asia" : resetBaseUrl;
        String link = base + "/portal/forget?token=" + token;
        try {
            MimeMessage m = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, "UTF-8");
            // 明确设置发件人：多数服务商要求与 SMTP 认证账号一致，否则拒发
            String from = (mailSmtpFrom != null && !mailSmtpFrom.isBlank()) ? mailSmtpFrom : mailUsername;
            h.setFrom(from);
            h.setSubject("PACC 密码重置");
            h.setText("您好：\n\n收到密码重置请求。请在 15 分钟内打开以下链接设置新密码：\n\n"
                    + link + "\n\n如非本人操作，请忽略此邮件。来自 PACC 反作弊系统。", false);
            mailSender.send(m);
            log.info("已向 {} 发送密码重置邮件", email);
        } catch (MessagingException e) {
            log.error("发送密码重置邮件失败 email={} err={}", email, e.getMessage());
        }
    }

    @PostMapping("/reset")
    public ResponseEntity<?> reset(@RequestBody Map<String, String> body) {
        try {
            accountService.resetPassword(body.get("token"), body.get("new_password"));
            return ResponseEntity.ok(Map.of("ok", true, "message", "密码已重置，请使用新密码登录"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** 发送验证码到邮箱/手机（scene: register / login / reset）。同 IP 与同目标均有限流。 */
    @PostMapping("/code/send")
    public ResponseEntity<?> sendCode(@RequestBody Map<String, String> body, HttpServletRequest request) {
        String target = body.get("target");
        String scene = body.getOrDefault("scene", "register");
        if (target == null || target.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入邮箱或手机号"));
        }
        String ip = clientIp(request);
        // 双维度限流：同一来源 IP + 同一目标，防批量轰炸
        if (!throttle.allowed("code:send:ip:" + ip) || !throttle.allowed("code:send:" + target)) {
            log.warn("验证码发送被限流 target={} ip={}", maskTarget(target), ip);
            return ResponseEntity.status(429).body(Map.of("error", "验证码发送过频，请稍后再试"));
        }
        String code = verifyCodeService.issue(target, scene);
        if (code == null) {
            // 同目标过短间隔内已发，不重复（对调用方保持幂等外观）
            return ResponseEntity.ok(Map.of("ok", true, "message", "验证码已发送"));
        }
        if (target.contains("@")) {
            if (mailStub) {
                log.info("[验证码stub] 发往 {} 的验证码={}", target, code);
            } else if (mailSender != null) {
                sendOtpMail(target, scene, code);
            } else {
                log.warn("SMTP 未配置且未启用 stub，验证码未发送 target={}", target);
                return ResponseEntity.ok(Map.of("ok", true, "message", "验证码已发送"));
            }
        } else {
            smsProvider.send(target, code);
        }
        return ResponseEntity.ok(Map.of("ok", true, "message", "验证码已发送"));
    }

    /** 校验验证码（一次性，不返回账号存在性等敏感信息）。 */
    @PostMapping("/code/verify")
    public ResponseEntity<?> verifyCode(@RequestBody Map<String, String> body) {
        String target = body.get("target");
        String scene = body.getOrDefault("scene", "register");
        String code = body.get("code");
        if (target == null || target.isBlank() || code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入目标与验证码"));
        }
        if (!verifyCodeService.verify(target, scene, code)) {
            return ResponseEntity.badRequest().body(Map.of("error", "验证码不正确或已过期"));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private void sendOtpMail(String email, String scene, String code) {
        String sceneLabel = switch (scene) {
            case "register" -> "注册";
            case "reset" -> "重置密码";
            default -> "登录";
        };
        try {
            MimeMessage m = mailSender.createMimeMessage();
            MimeMessageHelper h = new MimeMessageHelper(m, "UTF-8");
            String from = (mailSmtpFrom != null && !mailSmtpFrom.isBlank()) ? mailSmtpFrom : mailUsername;
            h.setFrom(from);
            h.setSubject("PACC " + sceneLabel + "验证码");
            h.setText("您好：\n\n您正在" + sceneLabel + "，本次验证码为：\n\n" + code
                    + "\n\n10 分钟内有效，如非本人操作请忽略。来自 PACC 反作弊系统。", false);
            mailSender.send(m);
            log.info("已向 {} 发送{}验证码", email, sceneLabel);
        } catch (MessagingException e) {
            log.error("发送{}验证码失败 email={} err={}", sceneLabel, email, e.getMessage());
        }
    }

    private static String maskTarget(String target) {
        if (target == null || target.length() < 4) return "****";
        return target.substring(0, 2) + "***" + target.substring(target.length() - 2);
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body, HttpServletRequest request,
                                   HttpServletResponse response) {
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
            String deviceFp = body.get("device_fingerprint");
            TokenService.Token token = accountService.login(identity, body.get("password"),
                    deviceFp, remember);
            throttle.clear(idScope);
            throttle.clear(ipScope);
            // 2FA：若该账号已启用两步验证，先判断当前设备是否已被信任；
            // 可信设备（30 天内验证过且未过期）免于二次验证，直接签发主会话。
            if (totpService.enabled(token.pteid())) {
                if (totpService.isTrustedDevice(token.pteid(), deviceFp)) {
                    log.info("玩家可信设备直登 pteid={}", token.pteid());
                    Account accT = accountService.findByPteidOrNull(token.pteid());
                    competitionService.recordLogin(token.pteid(),
                            accT == null ? null : accT.getDeviceFingerprint(), ip);
                    setPlayerCookie(response, token.accessToken(), token.expiresAt());
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("pteid", token.pteid());
                    out.put("expires_at", token.expiresAt());
                    return ResponseEntity.ok(out);
                }
                log.info("玩家开启 2FA，要求第二步验证 pteid={}", token.pteid());
                Map<String, Object> pending = new LinkedHashMap<>();
                pending.put("twofa_required", true);
                pending.put("pending", totpService.issuePending(token.pteid()));
                return ResponseEntity.ok(pending);
            }
            log.info("玩家登录成功 pteid={}", token.pteid());
            // 记账并触发代练/共享聚合检测
            Account acc = accountService.findByPteidOrNull(token.pteid());
            competitionService.recordLogin(token.pteid(),
                    acc == null ? null : acc.getDeviceFingerprint(), ip);
            // 会话令牌写入 HttpOnly 会话 cookie：JS 不可读，防 XSS 窃取
            setPlayerCookie(response, token.accessToken(), token.expiresAt());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("pteid", token.pteid());
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

    /** 登录第二步：校验短时 pending 令牌 + TOTP（或一次性恢复码），成功后签发主会话 cookie。 */
    @PostMapping("/login/2fa")
    public ResponseEntity<?> login2fa(@RequestBody Map<String, String> body, HttpServletRequest request,
                                      HttpServletResponse response) {
        String pending = body.get("pending");
        String code = body.get("code");
        if (pending == null || pending.isBlank() || code == null || code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请输入验证码"));
        }
        String pteid;
        try {
            pteid = totpService.parsePending(pending);
        } catch (SecurityException e) {
            return ResponseEntity.status(401).body(Map.of("error", e.getMessage()));
        }
        if (!totpService.validate(pteid, code)) {
            log.warn("2FA 第二步验证失败 pteid={}", pteid);
            return ResponseEntity.status(401).body(Map.of("error", "验证码不正确或已失效"));
        }
        boolean remember = Boolean.parseBoolean(body.getOrDefault("remember", "false"));
        // 信任此设备：勾选且提交真实 TOTP（非恢复码）时记录设备指纹，30 天内免再次二次验证。
        boolean trust = Boolean.parseBoolean(body.getOrDefault("trust_device", "false"));
        String deviceFp = body.get("device_fingerprint");
        if (trust && deviceFp != null && !deviceFp.isBlank() && totpService.isValidTotp(pteid, code)) {
            totpService.trustDevice(pteid, deviceFp, 30L);
        }
        TokenService.Token token = accountService.issueToken(pteid, remember);
        competitionService.recordLogin(pteid, accountFingerprint(pteid), clientIp(request));
        log.info("2FA 第二步验证成功 pteid={}", pteid);
        setPlayerCookie(response, token.accessToken(), token.expiresAt());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pteid", pteid);
        out.put("expires_at", token.expiresAt());
        return ResponseEntity.ok(out);
    }

    /** 查询某账号 2FA 状态（供安全中心只读展示，不暴露敏感密钥）。 */
    @GetMapping("/2fa/status")
    public ResponseEntity<?> twofaStatus(@RequestBody(required = false) Map<String, String> body,
                                         HttpServletRequest req) {
        // 登录前无法直接取 pteid：返回通用结构，仅当带 pending 或已登录质询时才可判定
        Object v = req.getAttribute("pteid");
        String pteid = v == null ? "" : v.toString();
        String pending = body == null ? null : body.get("pending");
        if (pteid.isBlank() && pending != null && !pending.isBlank()) {
            try {
                pteid = totpService.parsePending(pending);
            } catch (SecurityException ignored) {
                return ResponseEntity.ok(Map.of("enabled", false));
            }
        }
        boolean enabled = !pteid.isBlank() && totpService.enabled(pteid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        if (enabled) {
            out.put("trusted_devices", totpService.trustedDeviceCount(pteid));
        }
        return ResponseEntity.ok(out);
    }

    /** 撤销某可信设备（需已登录，携带需要移除的设备指纹）。 */
    @PostMapping("/2fa/trusted/revoke")
    public ResponseEntity<?> revokeTrustedDevice(@RequestBody Map<String, String> body,
                                                HttpServletRequest req) {
        Object v = req.getAttribute("pteid");
        String pteid = v == null ? "" : v.toString();
        if (pteid.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        }
        String deviceFp = body.get("device_fingerprint");
        if (deviceFp == null || deviceFp.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少设备指纹"));
        }
        boolean removed = totpService.revokeTrustedDevice(pteid, deviceFp);
        if (!removed) {
            return ResponseEntity.status(404).body(Map.of("error", "未找到该可信设备"));
        }
        return ResponseEntity.ok(Map.of("removed", true, "trusted_devices", totpService.trustedDeviceCount(pteid)));
    }

    private String accountFingerprint(String pteid) {
        Account acc = accountService.findByPteidOrNull(pteid);
        return acc == null ? null : acc.getDeviceFingerprint();
    }

    /** 登出：同路径/属性下发 Max-Age=0 的 cookie 使浏览器侧立即失效。 */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        response.addHeader("Set-Cookie", playerCookieHeader("", 0));
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 将玩家会话 JWT 写入 HttpOnly 安全 cookie。Only HTTPS 下发时附加 Secure。 */
    private static void setPlayerCookie(HttpServletResponse response, String token, long expiresAtMillis) {
        long now = System.currentTimeMillis();
        long maxAge = expiresAtMillis <= 0 ? 0 : Math.max(0, (expiresAtMillis - now) / 1000);
        response.addHeader("Set-Cookie", playerCookieHeader(token, maxAge));
    }

    private static String playerCookieHeader(String value, long maxAge) {
        StringBuilder sb = new StringBuilder("pacc_player=").append(value)
                .append("; Path=/; HttpOnly; SameSite=Lax");
        if (maxAge > 0) {
            sb.append("; Max-Age=").append(maxAge);
        }
        // Secure 属性由响应是否源自 HTTPS 决定：本地明文联调不加，生产 HTTPS 自动带
        if (secureResponseHint()) sb.append("; Secure");
        return sb.toString();
    }

    /**
     * 依据部署形态附加 Secure：网关/生产在 HTTPS 下发；本地 http 联调则省略。
     * 复位判定基于“当前请求是否安全”，此处以静态标记为准（生产部署时后端置于 TLS 网关后）。
     */
    private static boolean secureResponseHint() {
        return "https".equalsIgnoreCase(System.getenv("PACC_HTTPS_DEPLOY"))
                || Boolean.parseBoolean(System.getenv("PACC_HTTPS_DEPLOY"));
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