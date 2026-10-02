package com.potatotv.pacc.controller;

import com.potatotv.pacc.config.PtoKeyFactory;
import com.potatotv.pacc.service.TokenService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PTO 令牌对外端点：JWKS 公钥分发与刷新令牌续期。
 *
 * <p>JWKS 仅在 RS256 模式下提供——对称算法没有可公开的验证密钥；
 * 刷新令牌优先从 HttpOnly cookie 读取，桌面客户端也可放在请求体里。</p>
 */
@RestController
@RequestMapping("/api/v1/auth")
public class PtoAuthController {

    /** 玩家刷新令牌的 HttpOnly cookie 名。 */
    public static final String REFRESH_COOKIE = "pacc_refresh";

    private final PtoKeyFactory keys;
    private final TokenService tokenService;

    public PtoAuthController(PtoKeyFactory keys, TokenService tokenService) {
        this.keys = keys;
        this.tokenService = tokenService;
    }

    /** 公开当前签名公钥（JWKS 格式），供管理端前端与第三方离线校验令牌。 */
    @GetMapping("/jwks")
    public ResponseEntity<?> jwks() {
        if (!keys.isRsa() || !(keys.publicKey() instanceof RSAPublicKey rsa)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "当前未启用非对称签名"));
        }
        Map<String, Object> jwk = new LinkedHashMap<>();
        jwk.put("kty", "RSA");
        jwk.put("use", "sig");
        jwk.put("alg", "RS256");
        if (keys.kid() != null) {
            jwk.put("kid", keys.kid());
        }
        jwk.put("n", unsignedBase64(rsa.getModulus()));
        jwk.put("e", unsignedBase64(rsa.getPublicExponent()));
        return ResponseEntity.ok(Map.of("keys", List.of(jwk)));
    }

    /** 用刷新令牌换取新的访问令牌。 */
    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(value = REFRESH_COOKIE, required = false) String cookieToken,
                                     @RequestBody(required = false) Map<String, String> body) {
        String refreshToken = cookieToken != null && !cookieToken.isBlank()
                ? cookieToken
                : (body == null ? null : body.get("refresh_token"));
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少刷新令牌"));
        }
        String fingerprint = body == null ? null : body.get("device_fingerprint");
        try {
            TokenService.Token token = tokenService.refresh(refreshToken, fingerprint);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("access_token", token.accessToken());
            out.put("expires_at", token.expiresAt());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            // 刷新失败一律给通用文案，不暴露具体失败原因
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "刷新令牌无效或已过期"));
        }
    }

    /** JWK 要求无符号大端整数：去掉 BigInteger 正号补出的前导 0。 */
    private static String unsignedBase64(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            bytes = Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
