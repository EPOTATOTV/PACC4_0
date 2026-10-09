package com.potatotv.pto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * PTO（PACC Token）——零第三方依赖的 JWT 实现，支持 HS256 与 RS256。
 *
 * <p>相比 jjwt 的取舍：</p>
 * <ul>
 *   <li><b>算法固定</b>：实例创建时就锁定算法（HS256 或 RS256），签发只用这一种；
 *       校验时先核对 header.alg 必须等于实例算法，再无条件用该算法验签。alg 永远不参与
 *       「选算法」，从根上堵死 {@code alg:none} 与 RS/HS 混淆这类算法混淆攻击。</li>
 *   <li><b>非对称可选</b>：HS256 用于对称场景；RS256 用私钥签发、公钥验证，公钥可经 JWKS 公开分发。</li>
 *   <li><b>恒时比较</b>：HMAC 签名用 {@link MessageDigest#isEqual} 比较，不泄漏时序。</li>
 *   <li><b>必验时间窗</b>：exp 必须存在且未过期；带 nbf 时校验尚未生效。</li>
 *   <li><b>密钥长度下限</b>：HS256 密钥不足 32 字节直接拒绝，避免弱密钥。</li>
 * </ul>
 *
 * <p>明文密钥只保留内部副本，不对外暴露。</p>
 */
public final class PtoToken {

    public static final String ALG_HS256 = "HS256";
    public static final String ALG_RS256 = "RS256";

    private static final String SIGN_ALGORITHM_RSA = "SHA256withRSA";
    private static final int MIN_KEY_BYTES = 32;

    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] hmacKey;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String algorithm;
    private final String kid;
    private final String defaultIssuer;
    private final String headerB64;

    private PtoToken(byte[] hmacKey, PrivateKey privateKey, PublicKey publicKey,
                     String algorithm, String kid, String defaultIssuer) {
        this.hmacKey = hmacKey == null ? null : hmacKey.clone();
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.algorithm = algorithm;
        this.kid = kid;
        this.defaultIssuer = defaultIssuer;
        this.headerB64 = B64E.encodeToString(header(algorithm, kid).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * HS256 实例。
     *
     * @param key           HMAC 密钥，至少 32 字节
     * @param defaultIssuer 默认 issuer；签发时未显式指定则补上，校验时作为必须匹配的 issuer
     */
    public PtoToken(byte[] key, String defaultIssuer) {
        this(requireHmacKey(key), null, null, ALG_HS256, null, defaultIssuer);
    }

    /** 不绑定默认 issuer 的 HS256 实例，适合一把密钥签发多个 issuer 的场景（如 2FA pending 令牌）。 */
    public PtoToken(byte[] key) {
        this(key, null);
    }

    /** HS256 工厂方法。 */
    public static PtoToken hmac(byte[] secret, String issuer) {
        return new PtoToken(secret, issuer);
    }

    /** RS256 工厂方法：私钥签发、公钥验证。 */
    public static PtoToken rsa(PrivateKey privateKey, PublicKey publicKey, String issuer) {
        return rsa(privateKey, publicKey, issuer, null);
    }

    /** RS256 工厂方法，携带 {@code kid} 便于密钥轮换。 */
    public static PtoToken rsa(PrivateKey privateKey, PublicKey publicKey, String issuer, String kid) {
        Objects.requireNonNull(privateKey, "privateKey");
        Objects.requireNonNull(publicKey, "publicKey");
        return new PtoToken(null, privateKey, publicKey, ALG_RS256, kid, issuer);
    }

    private static byte[] requireHmacKey(byte[] key) {
        Objects.requireNonNull(key, "key");
        if (key.length < MIN_KEY_BYTES) {
            throw new IllegalArgumentException("PTO 密钥至少需要 " + MIN_KEY_BYTES + " 字节，当前 " + key.length);
        }
        return key;
    }

    private static String header(String algorithm, String kid) {
        Map<String, Object> h = new LinkedHashMap<>();
        h.put("alg", algorithm);
        h.put("typ", "JWT");
        if (kid != null && !kid.isBlank()) {
            h.put("kid", kid);
        }
        return PtoJson.write(h);
    }

    public String algorithm() {
        return algorithm;
    }

    /** 配置的密钥标识；未配置时返回 null。 */
    public String kid() {
        return kid;
    }

    /** RS256 的公钥；HS256 时返回 null。JWKS 端点据此公开公钥。 */
    public PublicKey publicKey() {
        return publicKey;
    }

    public boolean isRsa() {
        return ALG_RS256.equals(algorithm);
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

        // 1) 只认实例固定算法：header 里出现其它算法（含 none）直接拒绝
        Map<String, Object> header = PtoJson.readObject(decodeBase64(headerPart));
        if (!algorithm.equals(header.get("alg"))) {
            throw new PtoException("不支持的签名算法");
        }
        // 配置了 kid 时，header 里带了不一致的 kid 直接拒绝（密钥轮换场景）
        Object headerKid = header.get("kid");
        if (kid != null && !kid.isBlank() && headerKid != null && !kid.equals(String.valueOf(headerKid))) {
            throw new PtoException("签名密钥标识不匹配");
        }

        // 2) 验签
        byte[] actual;
        try {
            actual = B64D.decode(signaturePart);
        } catch (IllegalArgumentException e) {
            throw new PtoException("签名编码非法", e);
        }
        if (!verify((headerPart + "." + payloadPart).getBytes(StandardCharsets.UTF_8), actual)) {
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

    /** 用实例固定算法签名。 */
    public byte[] sign(byte[] data) {
        if (ALG_HS256.equals(algorithm)) {
            return hmac(data);
        }
        try {
            Signature sig = Signature.getInstance(SIGN_ALGORITHM_RSA);
            sig.initSign(privateKey);
            sig.update(data);
            return sig.sign();
        } catch (Exception e) {
            throw new PtoException("RSA 签名失败", e);
        }
    }

    /** 用实例固定算法验签；任何异常一律视为验签失败。 */
    public boolean verify(byte[] data, byte[] signature) {
        if (ALG_HS256.equals(algorithm)) {
            return MessageDigest.isEqual(hmac(data), signature);
        }
        try {
            Signature sig = Signature.getInstance(SIGN_ALGORITHM_RSA);
            sig.initVerify(publicKey);
            sig.update(data);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
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

    // ------------------------------ PEM 密钥解析 ------------------------------

    /** 解析 PKCS#8 私钥 PEM（{@code -----BEGIN PRIVATE KEY-----}）。 */
    public static PrivateKey parsePrivateKeyPem(String pem) {
        byte[] der = decodePem(pem, "PRIVATE KEY");
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        } catch (Exception e) {
            throw new PtoException("RSA 私钥解析失败", e);
        }
    }

    /** 解析 X.509 公钥 PEM（{@code -----BEGIN PUBLIC KEY-----}）。 */
    public static PublicKey parsePublicKeyPem(String pem) {
        byte[] der = decodePem(pem, "PUBLIC KEY");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
        } catch (Exception e) {
            throw new PtoException("RSA 公钥解析失败", e);
        }
    }

    private static byte[] decodePem(String pem, String label) {
        if (pem == null || pem.isBlank()) {
            throw new PtoException("PEM 内容为空");
        }
        // 环境变量注入时换行常被写成字面量 \n，这里一并还原
        String normalized = pem.replace("\\n", "\n").trim();
        String begin = "-----BEGIN " + label + "-----";
        String end = "-----END " + label + "-----";
        if (!normalized.contains(begin) || !normalized.contains(end)) {
            throw new PtoException("PEM 格式不匹配，期望 " + begin);
        }
        String body = normalized.substring(normalized.indexOf(begin) + begin.length(), normalized.indexOf(end))
                .replaceAll("\\s", "");
        if (body.isEmpty()) {
            throw new PtoException("PEM 内容为空");
        }
        try {
            return Base64.getDecoder().decode(body);
        } catch (IllegalArgumentException e) {
            throw new PtoException("PEM Base64 解码失败", e);
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

        /** 绑定设备指纹（claim {@code dfp}）。 */
        public Builder deviceFingerprint(String fingerprint) {
            return claim(PtoClaims.DFP, fingerprint);
        }

        /** 声明令牌用途（claim {@code type}，如 access / refresh）。 */
        public Builder tokenType(String type) {
            return claim(PtoClaims.TYPE, type);
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
            String signingInput = headerB64 + "." + payloadB64;
            String signature = B64E.encodeToString(PtoToken.this.sign(signingInput.getBytes(StandardCharsets.UTF_8)));
            return signingInput + "." + signature;
        }
    }
}
