package com.potatotv.pacc.service;

import com.potatotv.pacc.config.PinnedTrustManagerFactory;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * 飞书（Lark）开放平台 OAuth 登录。
 * <p>对公发布使用真实两段式流程：</p>
 * <ul>
 *   <li>enabled=false 时关闭（生产未配置飞书应用时的默认态）；</li>
 *   <li>enabled=true 时用 AppID/Secret 换取 access_token 并拉取用户身份，
 *       仅当用户在 super-admins / admins 白名单内才允许登录（默认拒绝，杜绝任意员工登录管理后台）；</li>
 *   <li>enabled=false 且 code 命中测试码时返回本地演示管理员（仅 local 联调）。</li>
 * </ul>
 * 出站 TLS 支持可选证书固定（见 {@link PinnedTrustManagerFactory}）。
 */
@Service
public class FeishuAuthService {

    private static final Logger log = LoggerFactory.getLogger(FeishuAuthService.class);

    private static final String FEISHU_OPEN_BASE = "https://open.feishu.cn/open-apis";

    private final AtomicReference<String> issuedCode = new AtomicReference<>();

    private RestClient client;

    public FeishuAuthService(PinnedTrustManagerFactory tlsPin) {
        RestClient.Builder builder = RestClient.builder().baseUrl(FEISHU_OPEN_BASE);
        if (tlsPin.active()) {
            builder.requestFactory(tlsPin.requestFactory());
        }
        this.client = builder.build();
    }

    @Value("${pacc.feishu.enabled:false}")
    private boolean enabled;

    @Value("${pacc.feishu.app-id:}")
    private String appId;

    @Value("${pacc.feishu.app-secret:}")
    private String appSecret;

    @Value("${pacc.feishu.super-admin-userids:}")
    private String superAdminUserIds;

    @Value("${pacc.feishu.admin-userids:}")
    private String adminUserIds;

    @Value("${pacc.feishu.test-code:FEISHU_TEST_CODE_2026}")
    private String testCode;

    @Value("${pacc.feishu.test-user-id:admin_feishu_stub}")
    private String testUserId;

    @Value("${pacc.feishu.test-user-name:飞书管理员（演示）}")
    private String testUserName;

    /** 飞书用户画像。role 为 null 表示未授权（登录应拒绝）。 */
    public record FeishuUser(String userId, String name, String email, String mobile, String role) {}

    /** 飞书集成是否启用（未配置企业应用时保持 false）。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 构造 OAuth 授权地址并登记 state（防 CSRF）。生产指向真实飞书授权端点；未启用时为本地回环 stub。 */
    public String buildAuthorizeUrl(String redirectUri, String state) {
        issuedCode.set(state);
        if (!enabled) {
            return "https://pacc-feishu.local/oauth/authorize?app_id=" + appId
                    + "&redirect_uri=" + redirectUri + "&code=" + state;
        }
        return FEISHU_OPEN_BASE + "/authen/v1/authorize?app_id=" + appId
                + "&redirect_uri=" + redirectUri + "&state=" + state;
    }

    /** 校验回调 state 是否为本会话签发，防 OAuth 回调伪造/重放。 */
    public boolean validateState(String state) {
        if (state == null || state.isBlank()) return false;
        String issued = issuedCode.getAndSet(null);
        return state.equals(issued);
    }

    /** 用授权 code 换取用户身份并按白名单判定角色；未授权返回 empty。 */
    public Optional<FeishuUser> exchange(String code) {
        if (code == null || code.isBlank()) return Optional.empty();

        if (!enabled) {
            // 本地联调 stub：enabled=false 且命中测试码才放行
            if (testCode.equals(code)) {
                log.info("飞书登录(stub) 通过测试码 userId={}", testUserId);
                return Optional.of(new FeishuUser(testUserId, testUserName,
                        testUserId + "@feishu.local", "13800000000", "super-admin"));
            }
            log.warn("飞书(未启用) 收到未知 code，拒绝");
            return Optional.empty();
        }

        try {
            long t0 = System.currentTimeMillis();
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = client.post()
                    .uri("/authen/v1/access_token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("app_id", appId, "app_secret", appSecret, "code", code))
                    .retrieve()
                    .body(Map.class);
            Object codeObj = resp.get("code");
            if (codeObj == null || !"0".equals(String.valueOf(codeObj))) {
                log.error("飞书换取 access_token 失败 code={} msg={}", codeObj, resp.get("msg"));
                return Optional.empty();
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) resp.get("data");
            if (data == null) return Optional.empty();
            String userId = str(data.get("open_id"));
            // access_token 接口通常只保证 open_id / union_id，未消费（user_access_token 不应落日志）
            String userAccessToken = str(data.get("access_token"));
            String name = str(data.get("name"));
            if (name.isBlank()) name = str(data.get("union_id"));
            String email = str(data.get("email"));
            if (email.isBlank()) email = str(data.get("enterprise_email"));
            String mobile = "";
            // 手机号/企业邮箱不随换 token 稳定返回，需用 user_access_token 二次拉通讯录用户详情
            if (!userAccessToken.isBlank()) {
                Map<String, Object> detail = fetchUserDetail(userId, userAccessToken);
                if (detail != null) {
                    if (email.isBlank()) email = str(detail.get("email"));
                    if (email.isBlank()) email = str(detail.get("enterprise_email"));
                    mobile = str(detail.get("mobile"));
                    mobile = normalizeMobile(mobile);
                } else {
                    log.warn("飞书二次拉取用户详情失败（无手机号权限或接口受限），仅靠 open_id 鉴权 userId={}", userId);
                }
            }
            log.info("飞书换取身份成功 open_id={} name={} email={} mobile={} 耗时{}ms", userId, name,
                    email.isBlank() ? "-" : email, mobile.isBlank() ? "-" : mobile,
                    System.currentTimeMillis() - t0);

            String role = roleOf(userId, email, mobile);
            if (role == null) {
                log.warn("飞书用户未在白名单，拒绝登录 userId={} name={} email={} mobile={}", userId, name,
                        email.isBlank() ? "-" : email, mobile.isBlank() ? "-" : mobile);
                return Optional.empty();
            }
            return Optional.of(new FeishuUser(userId, name, email, mobile, role));
        } catch (Exception e) {
            log.warn("飞书 OAuth 调用异常: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 用 user_access_token 拉取通讯录用户详情，返回其在通讯录中的邮箱/手机号。
     * 需要应用开通通讯录读取权限；无权限时返回 null（鉴权退化为 open_id 匹配）。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchUserDetail(String openId, String userAccessToken) {
        try {
            Map<String, Object> resp = client.get()
                    .uri("/contact/v3/users/{user_id}?user_id_type=open_id", openId)
                    .header("Authorization", "Bearer " + userAccessToken)
                    .retrieve()
                    .body(Map.class);
            Object codeObj = resp.get("code");
            if (codeObj == null || !"0".equals(String.valueOf(codeObj))) {
                log.warn("飞书拉取用户详情失败 code={} msg={}", codeObj, resp.get("msg"));
                return null;
            }
            return (Map<String, Object>) resp.get("data");
        } catch (Exception e) {
            log.warn("飞书拉取用户详情异常: {}", e.getMessage());
            return null;
        }
    }

    /** 手机号归一化：去空格/连字符，避免格式差异导致白名单不匹配。 */
    private static String normalizeMobile(String v) {
        if (v == null || v.isBlank()) return "";
        return v.replaceAll("[^0-9+]", "");
    }

    /** 依据 open_id / email / mobile 匹配配置白名单；任一命中即授权。 */
    private String roleOf(String userId, String email, String mobile) {
        if (in(superAdminUserIds, userId, email, mobile)) return "super-admin";
        if (in(adminUserIds, userId, email, mobile)) return "operator";
        return null; // 默认拒绝
    }

    private static boolean in(String csv, String userId, String email, String mobile) {
        if (csv == null || csv.isBlank()) return false;
        for (String s : csv.split(",")) {
            String v = s.trim();
            if (v.isEmpty()) continue;
            if (v.equals(userId)
                    || (email != null && v.equals(email))
                    || (mobile != null && normalizeMobile(mobile).equals(normalizeMobile(v)))) {
                return true;
            }
        }
        return false;
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }
}