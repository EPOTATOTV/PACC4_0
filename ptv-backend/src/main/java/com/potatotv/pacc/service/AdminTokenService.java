package com.potatotv.pacc.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * 管理员会话令牌（JWT，含 role 声明）。用于飞书登录成功后签发管理员会话，
 * 由管理后台的 {@code com.potatotv.pacc.config.AdminKeyFilter} 验签放行。
 */
@Service
public class AdminTokenService {

    public static final String ISSUER = "pacc-ptv-admin";
    public static final String AUDIENCE = "pacc-admin-console";

    private final SecretKey key;

    public AdminTokenService(@Value("${pacc.security.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /** 会话指纹 claim 名。绑定的目的是：令牌被盗后在其它设备/IP 上无法使用。 */
    public static final String FP_CLAIM = "fp";

    public String create(String identity, String role) {
        return create(identity, role, null);
    }

    /**
     * 签发管理员会话令牌。当传入 {@code fingerprint} 时，将 IP+UA 摘要写入 claim，
     * 后续每个管理请求都会校验来源指纹一致，防止令牌跨设备冒用。
     */
    public String create(String identity, String role, String fingerprint) {
        Instant exp = Instant.now().plus(Duration.ofHours(12));
        return Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(identity)
                .claim("role", role)
                .claim(FP_CLAIM, fingerprint == null ? "" : fingerprint)
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
    }

    /** 校验并返回 role；非法返回 null（供过滤器判 401）。 */
    public String parseRole(String token) {
        Claims c = parse(token);
        return c == null ? null : c.get("role", String.class);
    }

    /** 返回令牌主体（管理员身份）；非法返回 null。 */
    public String identityOf(String token) {
        Claims c = parse(token);
        return c == null ? null : c.getSubject();
    }

    /**
     * 校验令牌并核对来源指纹。指纹不匹配（令牌被拿到其它设备上使用）时视为无效。
     * 兼容旧令牌：未带指纹 claim 的令牌视为可接受（仅对新令牌强制绑定）。
     */
    public String parseRoleWithFingerprint(String token, String fingerprint) {
        Claims c = parse(token);
        if (c == null) return null;
        Object bound = c.get(FP_CLAIM);
        if (bound instanceof String b && !b.isBlank()) {
            if (fingerprint == null || !java.security.MessageDigest.isEqual(
                    b.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                return null;
            }
        }
        return c.get("role", String.class);
    }

    private Claims parse(String token) {
        try {
            Claims c = Jwts.parser().requireIssuer(ISSUER).verifyWith(key).build()
                    .parseSignedClaims(token).getPayload();
            if (!c.getAudience().contains(AUDIENCE)) return null;
            return c;
        } catch (Exception e) {
            return null;
        }
    }
}