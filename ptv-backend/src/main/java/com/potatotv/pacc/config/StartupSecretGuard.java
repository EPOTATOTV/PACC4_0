package com.potatotv.pacc.config;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 生产（非 local）启动守卫：对公上线必须经环境变量注入真实密钥。
 * <ul>
 *   <li>加密相关密钥（admin / super-admin / jwt / wss-sign）缺失、过短或等于已公开的演示默认值时，
 *       应用在启动阶段直接抛错终止（fail-closed），绝不静默退回弱默认值；</li>
 *   <li>密码找回未走 stub 时，SMTP 必须配置，否则同样启动失败（避免"看起来发了其实没发"）。</li>
 * </ul>
 * local profile 不校验，保留演示默认值便于本地联调。
 */
@Component
@Profile("!local")
@SuppressWarnings("null")
public class StartupSecretGuard {

    private static final Logger log = LoggerFactory.getLogger(StartupSecretGuard.class);

    /** 已公开到文档/镜像的演示弱值，命中任一即拒绝。 */
    private static final List<String> WEAK_STARTS = List.of(
            "pacc-admin-secret-key", "pacc-super-admin-secret-key", "pacc-dev-", "change-me");
    /** 已知不可用但常被误配的占位字符串。 */
    private static final List<String> WEAK_EXACT = List.of(
            "PACC-v4-runtime-secret-key-change-me-in-production-0123456789",
            "admin", "password", "secret");

    @Value("${pacc.security.admin-api-key}")
    private String adminApiKey;
    @Value("${pacc.security.super-admin-key}")
    private String superAdminKey;
    @Value("${pacc.security.jwt-secret}")
    private String jwtSecret;
    @Value("${pacc.security.wss-sign-secret}")
    private String wssSignSecret;
    @Value("${pacc.mail.stub-enabled}")
    private boolean mailStub;
    @Value("${spring.mail.host}")
    private String smtpHost;
    @Value("${pacc.feishu.enabled}")
    private boolean feishuEnabled;
    @Value("${pacc.feishu.app-id}")
    private String feishuAppId;

    @PostConstruct
    void enforce() {
        Map<String, String> problems = new LinkedHashMap<>();

        problems.put("PACC_SECURITY_ADMIN_API_KEY", checkSecret(adminApiKey, 12));
        problems.put("PACC_SECURITY_SUPER_ADMIN_KEY", checkSecret(superAdminKey, 12));
        problems.put("PACC_SECURITY_JWT_SECRET", checkSecret(jwtSecret, 32));
        problems.put("PACC_SECURITY_WSS_SIGN_SECRET", checkSecret(wssSignSecret, 16));

        if (!mailStub && isBlank(smtpHost)) {
            problems.put("SMTP_HOST", "密码找回未走 stub，必须配置真实 SMTP 服务器");
        }
        if (feishuEnabled && isBlank(feishuAppId)) {
            problems.put("PACC_FEISHU_APP_ID", "已开启飞书登录但未配置应用 ID");
        }

        problems.entrySet().removeIf(e -> e.getValue() == null);
        if (problems.isEmpty()) {
            log.info("启动密钥守卫：生产密钥均已通过校验（fail-closed 就绪）");
            return;
        }

        StringBuilder sb = new StringBuilder("生产环境密钥校验未通过，启动终止（fail-closed）。"
                + "请通过环境变量注入真实密钥：\n");
        for (Map.Entry<String, String> e : problems.entrySet()) {
            sb.append("  - ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
        }
        throw new IllegalStateException(sb.toString());
    }

    /** 返回 null 表示通过；否则返回失败原因。 */
    private String checkSecret(String value, int minLength) {
        if (isBlank(value)) return "未配置（空值）";
        if (value.length() < minLength) return "长度过短（< " + minLength + "）";
        String lower = value.toLowerCase();
        if (WEAK_EXACT.stream().anyMatch(lower::contains)) return "命中已公开的演示默认值，请更换";
        if (WEAK_STARTS.stream().anyMatch(lower::startsWith)) return "命中已公开的演示默认前缀，请更换";
        return null;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}