package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.service.AccountService;
import com.potatotv.pacc.service.CompetitionService;
import com.potatotv.pacc.service.LoginThrottle;
import com.potatotv.pacc.service.TokenService;
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
            TokenService.Token token = accountService.login(identity, body.get("password"),
                    body.get("device_fingerprint"), remember);
            throttle.clear(idScope);
            throttle.clear(ipScope);
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