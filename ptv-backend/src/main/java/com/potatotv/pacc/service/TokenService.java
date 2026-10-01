package com.potatotv.pacc.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT 签发/校验。
 * <p>设计文档指定 RS256；演示实现使用 HMAC（RS256 需配置 RSA 公私钥对，生产启用）。</p>
 */
@Service
public class TokenService {

    public static final String ISSUER = "pacc-ptv";
    public static final String AUDIENCE = "pacc-client";

    public record Token(String pteid, String accessToken, long expiresAt) {}

    private final SecretKey key;
    private final long defaultTtlSeconds = 7L * 24 * 3600; // 默认 7 天

    public TokenService(@Value("${pacc.security.jwt-secret}") String secret) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Token createToken(String pteid, boolean remember) {
        long ttl = remember ? defaultTtlSeconds : 24 * 3600;
        Instant exp = Instant.now().plusSeconds(ttl);
        String jwt = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(pteid)
                .expiration(Date.from(exp))
                .signWith(key)
                .compact();
        return new Token(pteid, jwt, exp.toEpochMilli());
    }

    /** 校验并返回 pteid；非法抛出异常。 */
    public String parseAndGetPteid(String bearer) {
        String token = bearer.replace("Bearer ", "");
        return Jwts.parser().requireIssuer(ISSUER).verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }

    /** 校验并返回 pteid（供握手拦截器调用）；非法抛出异常。 */
    public String verify(String token) {
        return Jwts.parser().requireIssuer(ISSUER).verifyWith(key).build()
                .parseSignedClaims(token).getPayload().getSubject();
    }
}