package com.potatotv.pto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PTO（PACC Token）——零第三方依赖的 JWT 实现，只支持 HS256（HMAC-SHA256）。
 *
 * <p>相比 jjwt 的取舍：</p>
 * <ul>
 *   <li><b>算法固定</b>：签发恒用 HS256；校验时先核对 header.alg 必须是 HS256，
 *       再无条件用 HMAC-SHA256 验签。alg 永远不参与「选算法」，从根上堵死
 *       {@code alg:none} 与 RS/HS 混淆这类算法混淆攻击。</li>
 *   <li><b>恒时比较</b>：签名用 {@link MessageDigest#isEqual} 比较，不泄漏时序。</li>
 *   <li><b>必验时间窗</b>：exp 必须存在且未过期；带 nbf 时校验尚未生效。</li>
 *   <li><b>密钥长度下限</b>：不足 32 字节直接拒绝，避免弱密钥。</li>
 * </ul>
 *
 * <p>明文密钥只保留内部副本，不对外暴露。</p>
 */
public final class PtoToken {

    /** 固定的 header。PTO 只签发这一种 header，不因调用方而变。 */
    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
    private static final String ALG_HS256 = "HS256";
    private static final int MIN_KEY_BYTES = 32;

    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final String HEADER_B64 = B64E.encodeToString(HEADER.getBytes(StandardCharsets.UTF_8));

    private final byte[] key;
    private final String defaultIssuer;

    /**
     * @param key            HMAC 密钥，至少 32 字节
     * @param defaultIssuer  默认 issuer；签发时未显式指定则补上，校验时作为必须匹配的 issuer
     */
    public PtoToken(byte[] key, String defaultIssuer) {
        Objects.requireNonNull(key, "key");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("PTO 密钥至少需要 " + MIN_KEY_BYTES + " 字节，当前 " + key.length);
        }
        this.key = key.clone();
        this.defaultIssuer = defaultIssuer;
    }

    /** 不绑定默认 issuer 的实例，适合一把密钥签发多个 issuer 的场景（如 2FA pending 令牌）。 */
    public PtoToken(byte[] key) {
        this(key, null);
    }

    public Builder builder() {
        return new Builder();
    }

    /** 用默认 issuer 校验。 */
    public PtoClaims verify(String token) {
        return verify(token, defaultIssuer);
    }

    /**
     * 校验令牌并返回载荷。
     *
     * @param token          待校验令牌
     * @param expectedIssuer 期望 issuer；为 null 时不校验 issuer 字段
     * @throws PtoException 格式、算法、签名、issuer 或时间窗任一不通过
     */
    public PtoClaims verify(String token, String expectedIssuer) {
        if (token == null || token.isBlank()) {
            throw new PtoException("令牌为空");
        }
        int firstDot = token.indexOf('.');
        int lastDot = token.lastIndexOf('.');
        if (firstDot <= 0 || lastDot <= firstDot || lastDot == token.length() - 1
                || token.indexOf('.', firstDot + 1) != lastDot) {
            throw new PtoException("令牌格式错误");
        }
        String headerPart = token.substring(0, firstDot);
        String payloadPart = token.substring(firstDot + 1, lastDot);
        String signaturePart = token.substring(lastDot + 1);

        // 1) 只认 HS256：header 里出现其它算法（含 none）直接拒绝
        Map<String, Object> header = PtoJson.readObject(decodeBase64(headerPart));
        if (!ALG_HS256.equals(header.get("alg"))) {
            throw new PtoException("不支持的签名算法");
        }

        // 2) 恒时比较验签
        byte[] expected = hmac((headerPart + "." + payloadPart).getBytes(StandardCharsets.UTF_8));
        byte[] actual;
        try {
            actual = B64D.decode(signaturePart);
        } catch (IllegalArgumentException e) {
            throw new PtoException("签名编码非法", e);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new PtoException("签名无效");
        }

        // 3) 载荷
        PtoClaims claims = new PtoClaims(PtoJson.readObject(decodeBase64(payloadPart)));

        // 4) issuer
        if (expectedIssuer != null && !expectedIssuer.equals(claims.issuer())) {
            throw new PtoException("issuer 不匹配");
        }

        // 5) 时间窗：exp 必填
        long now = Instant.now().getEpochSecond();
        if (!claims.has("exp")) {
            throw new PtoException("令牌缺少 exp");
        }
        if (now > claims.expiresAt()) {
            throw new PtoException("令牌已过期");
        }
        if (claims.has("nbf") && now < claims.notBefore()) {
            throw new PtoException("令牌尚未生效");
        }
        return claims;
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new PtoException("HMAC 计算失败", e);
        }
    }

    private static String decodeBase64(String part) {
        try {
            return new String(B64D.decode(part), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new PtoException("Base64Url 解码失败", e);
        }
    }

    /**
     * 令牌构造器。exp 由调用方显式给出，避免默认值与业务语义脱节。
     */
    public final class Builder {

        private final Map<String, Object> claims = new LinkedHashMap<>();

        private Builder() {
        }

        public Builder issuer(String issuer) {
            if (issuer != null) {
                claims.put("iss", issuer);
            }
            return this;
        }

        public Builder subject(String subject) {
            if (subject != null) {
                claims.put("sub", subject);
            }
            return this;
        }

        public Builder audience(String... audience) {
            if (audience != null && audience.length > 0) {
                claims.put("aud", List.of(audience));
            }
            return this;
        }

        public Builder id(String id) {
            if (id != null) {
                claims.put("jti", id);
            }
            return this;
        }

        /** 自定义 claim。value 为 null 时忽略（JWT 中缺失即等价）。 */
        public Builder claim(String name, Object value) {
            if (name != null && value != null) {
                claims.put(name, value);
            }
            return this;
        }

        public Builder issuedAt(Instant at) {
            if (at != null) {
                claims.put("iat", at.getEpochSecond());
            }
            return this;
        }

        public Builder expiresAt(Instant at) {
            if (at != null) {
                claims.put("exp", at.getEpochSecond());
            }
            return this;
        }

        /** 签发。<code>Header.Payload.Signature</code> 三段 Base64Url 拼接。 */
        public String sign() {
            if (defaultIssuer != null) {
                claims.putIfAbsent("iss", defaultIssuer);
            }
            String payloadB64 = B64E.encodeToString(PtoJson.write(claims).getBytes(StandardCharsets.UTF_8));
            String signingInput = HEADER_B64 + "." + payloadB64;
            String signature = B64E.encodeToString(hmac(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        }
    }
}