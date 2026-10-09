package com.potatotv.pacc.service;

import com.potatotv.pacc.config.PtoKeyFactory;
import com.potatotv.pto.PtoClaims;
import com.potatotv.pto.PtoException;
import com.potatotv.pto.PtoToken;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * JWT 签发/校验（自研 PTO，零第三方依赖）。
 *
 * <p>算法由 {@link PtoKeyFactory} 依据 {@code pacc.security.jwt-algorithm} 决定，
 * 生产环境可切到 RS256（公钥经 JWKS 公开），默认仍是 HS256。</p>
 *
 * <p>令牌可绑定设备指纹（claim {@code dfp}）：签发时带上，校验时不匹配即拒绝，
 * 令牌被复制到其它设备也无法使用。刷新令牌通过 claim {@code type=refresh} 与访问令牌区分。</p>
 */
@Service
public class TokenService {

    public static final String ISSUER = "pacc-ptv";
    public static final String AUDIENCE = "pacc-client";

    /** 访问令牌用途。 */
    public static final String TYPE_ACCESS = "access";
    /** 刷新令牌用途。 */
    public static final String TYPE_REFRESH = "refresh";

    private static final long ACCESS_TTL_SECONDS = 24 * 3600;         // 普通登录 24 小时
    private static final long REMEMBER_TTL_SECONDS = 7L * 24 * 3600;  // 勾选记住我 7 天
    private static final long REFRESH_TTL_SECONDS = 7L * 24 * 3600;   // 刷新令牌 7 天

    public record Token(String pteid, String accessToken, long expiresAt) {}

    /** 访问令牌 + 刷新令牌。刷新令牌有效期更长，用于免登录续期。 */
    public record TokenPair(String pteid,
                            String accessToken, long accessExpiresAt,
                            String refreshToken, long refreshExpiresAt) {}

    private final PtoToken pto;

    public TokenService(PtoKeyFactory keys) {
        this.pto = keys.create(ISSUER);
    }

    public Token createToken(String pteid, boolean remember) {
        return createToken(pteid, null, remember);
    }

    /** 签发访问令牌；传入设备指纹时写入 dfp claim。 */
    public Token createToken(String pteid, String deviceFingerprint, boolean remember) {
        Instant exp = Instant.now().plusSeconds(remember ? REMEMBER_TTL_SECONDS : ACCESS_TTL_SECONDS);
        String jwt = baseBuilder(pteid, deviceFingerprint)
                .tokenType(TYPE_ACCESS)
                .expiresAt(exp)
                .sign();
        return new Token(pteid, jwt, exp.toEpochMilli());
    }

    /** 同时签发访问令牌与刷新令牌。 */
    public TokenPair createTokenPair(String pteid, String deviceFingerprint, boolean remember) {
        Instant now = Instant.now();
        Instant accessExp = now.plusSeconds(remember ? REMEMBER_TTL_SECONDS : ACCESS_TTL_SECONDS);
        Instant refreshExp = now.plusSeconds(REFRESH_TTL_SECONDS);
        String access = baseBuilder(pteid, deviceFingerprint)
                .tokenType(TYPE_ACCESS).expiresAt(accessExp).sign();
        String refresh = baseBuilder(pteid, deviceFingerprint)
                .tokenType(TYPE_REFRESH).expiresAt(refreshExp).sign();
        return new TokenPair(pteid, access, accessExp.toEpochMilli(), refresh, refreshExp.toEpochMilli());
    }

    /** 校验并返回 pteid；非法抛出异常。 */
    public String parseAndGetPteid(String bearer) {
        String token = bearer.replace("Bearer ", "");
        return verify(token);
    }

    /** 校验并返回 pteid（供握手拦截器调用）；非法抛出异常。 */
    public String verify(String token) {
        return pto.verify(token).subject();
    }

    /** 校验并返回完整载荷；非法抛出异常。 */
    public PtoClaims verifyClaims(String token) {
        return pto.verify(token);
    }

    /** 校验访问令牌并核对设备指纹；未绑定指纹的旧令牌放行，绑定后不匹配即拒绝。 */
    public String verifyWithDevice(String token, String expectedFingerprint) {
        PtoClaims claims = pto.verify(token);
        requireDevice(claims, expectedFingerprint);
        return claims.subject();
    }

    /**
     * 用刷新令牌换取新的访问令牌。
     * <p>刷新令牌必须用途为 refresh，且设备指纹与绑定时一致；返回的新访问令牌沿用原绑定。</p>
     */
    public Token refresh(String refreshToken, String deviceFingerprint) {
        PtoClaims claims = pto.verify(refreshToken);
        if (!TYPE_REFRESH.equals(claims.type())) {
            throw new PtoException("刷新令牌用途不符");
        }
        requireDevice(claims, deviceFingerprint);
        String bound = claims.deviceFingerprint() != null ? claims.deviceFingerprint() : deviceFingerprint;
        return createToken(claims.subject(), bound, true);
    }

    private PtoToken.Builder baseBuilder(String pteid, String deviceFingerprint) {
        return pto.builder()
                .audience(AUDIENCE)
                .subject(pteid)
                .claim(PtoClaims.DFP, deviceFingerprint);
    }

    private static void requireDevice(PtoClaims claims, String expectedFingerprint) {
        String bound = claims.deviceFingerprint();
        if (bound == null || bound.isBlank()) {
            return; // 兼容未绑定设备指纹的旧令牌
        }
        if (expectedFingerprint == null || !MessageDigest.isEqual(
                bound.getBytes(StandardCharsets.UTF_8), expectedFingerprint.getBytes(StandardCharsets.UTF_8))) {
            throw new PtoException("设备指纹不匹配");
        }
    }
}
