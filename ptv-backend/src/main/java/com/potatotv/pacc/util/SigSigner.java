package com.potatotv.pacc.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/**
 * 特征库热更新的内容签名器（v4.7）。
 * <p>对「digest + libraryVersion」做 HMAC-SHA256 签名，供客户端校验 diff 包未被篡改。
 * 密钥取自环境变量 PACC_SIG_SECRET（经 application.yml 映射）；local 联调用测试兜底值，
 * 非 local 环境缺失/空白则直接抛异常阻止启动（fail-closed，风格参考 StartupSecretGuard）。</p>
 */
@Component
public class SigSigner {

    /** 本地联调兜底密钥；命中即视为未正确注入（仅 local 放行）。 */
    private static final String TEST_SECRET = "pacc-sig-test-secret";

    private final String secret;

    public SigSigner(@Value("${pacc.security.sig-secret:}") String secret, Environment env) {
        String raw = (secret == null || secret.isBlank()) ? TEST_SECRET : secret;
        boolean local = env.acceptsProfiles(Profiles.of("local"));
        if (TEST_SECRET.equals(raw) && !local) {
            throw new IllegalStateException("生产环境缺失特征库签名密钥，拒绝启动（fail-closed）。"
                    + " 请通过环境变量注入 PACC_SIG_SECRET。");
        }
        this.secret = raw;
    }

    /** 对给定内容做 HMAC-SHA256 签名，返回 hex（小写）。 */
    public String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("特征库签名计算失败", e);
        }
    }
}