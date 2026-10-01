package com.potatotv.pacc.service;

import com.potatotv.pto.PtoToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * JWT 签发/校验（自研 PTO，零第三方依赖，固定 HS256）。
 * <p>设计文档指定 RS256；演示实现使用 HMAC（RS256 需配置 RSA 公私钥对，生产启用）。</p>
 */
@Service
public class TokenService {

    public static final String ISSUER = "pacc-ptv";
    public static final String AUDIENCE = "pacc-client";

    public record Token(String pteid, String accessToken, long expiresAt) {}

    private final PtoToken pto;
    private final long defaultTtlSeconds = 7L * 24 * 3600; // 默认 7 天

    public TokenService(@Value("${pacc.security.jwt-secret}") String secret) {
        this.pto = new PtoToken(secret.getBytes(StandardCharsets.UTF_8), ISSUER);
    }

    public Token createToken(String pteid, boolean remember) {
        long ttl = remember ? defaultTtlSeconds : 24 * 3600;
        Instant exp = Instant.now().plusSeconds(ttl);
        String jwt = pto.builder()
                .audience(AUDIENCE)
                .subject(pteid)
                .expiresAt(exp)
                .sign();
        return new Token(pteid, jwt, exp.toEpochMilli());
    }

    /** 校验并返回 pteid；非法抛出异常。 */
    public String parseAndGetPteid(String bearer) {
        String token = bearer.replace("Bearer ", "");
        return pto.verify(token).subject();
    }

    /** 校验并返回 pteid（供握手拦截器调用）；非法抛出异常。 */
    public String verify(String token) {
        return pto.verify(token).subject();
    }
}