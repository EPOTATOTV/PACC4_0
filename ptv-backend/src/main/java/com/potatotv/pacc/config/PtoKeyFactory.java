package com.potatotv.pacc.config;

import com.potatotv.pto.PtoToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * PTO 令牌密钥装配。
 *
 * <p>按 {@code pacc.security.jwt-algorithm} 决定对称（HS256）还是非对称（RS256）：
 * HS256 用 {@code jwt-secret}；RS256 用 {@code jwt-private-key} 签发、{@code jwt-public-key} 验证，
 * 公钥可经 {@code GET /api/v1/auth/jwks} 公开分发。</p>
 *
 * <p>配置不合法（算法未知、HS256 密钥过短、RS256 缺失密钥对）时构造即抛错，
 * 宁可启动失败也不静默退回弱算法。</p>
 */
@Component
public class PtoKeyFactory {

    private final String algorithm;
    private final String kid;
    private final byte[] hmacSecret;
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public PtoKeyFactory(@Value("${pacc.security.jwt-algorithm:HS256}") String algorithm,
                         @Value("${pacc.security.jwt-secret:}") String secret,
                         @Value("${pacc.security.jwt-private-key:}") String privateKeyPem,
                         @Value("${pacc.security.jwt-public-key:}") String publicKeyPem,
                         @Value("${pacc.security.jwt-kid:}") String kid) {
        String alg = algorithm == null ? "" : algorithm.trim().toUpperCase();
        this.kid = (kid == null || kid.isBlank()) ? null : kid.trim();
        switch (alg) {
            case PtoToken.ALG_HS256 -> {
                byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
                if (bytes.length < 32) {
                    throw new IllegalStateException("HS256 模式需要至少 32 字节的 pacc.security.jwt-secret");
                }
                this.algorithm = PtoToken.ALG_HS256;
                this.hmacSecret = bytes;
                this.privateKey = null;
                this.publicKey = null;
            }
            case PtoToken.ALG_RS256 -> {
                if (privateKeyPem == null || privateKeyPem.isBlank()
                        || publicKeyPem == null || publicKeyPem.isBlank()) {
                    throw new IllegalStateException(
                            "RS256 模式需要 pacc.security.jwt-private-key 与 pacc.security.jwt-public-key");
                }
                this.algorithm = PtoToken.ALG_RS256;
                this.hmacSecret = null;
                this.privateKey = PtoToken.parsePrivateKeyPem(privateKeyPem);
                this.publicKey = PtoToken.parsePublicKeyPem(publicKeyPem);
            }
            default -> throw new IllegalStateException("不支持的令牌签名算法：" + algorithm);
        }
    }

    public String algorithm() {
        return algorithm;
    }

    public String kid() {
        return kid;
    }

    public boolean isRsa() {
        return PtoToken.ALG_RS256.equals(algorithm);
    }

    /** RS256 公钥；HS256 时返回 null。 */
    public PublicKey publicKey() {
        return publicKey;
    }

    /** 不绑定默认 issuer 的令牌实例。 */
    public PtoToken create() {
        return create(null);
    }

    /**
     * 创建绑定指定默认 issuer 的令牌实例。
     * <p>签名密钥材料被就地复制给每个实例，实例之间互不影响。</p>
     */
    public PtoToken create(String issuer) {
        if (PtoToken.ALG_RS256.equals(algorithm)) {
            return PtoToken.rsa(privateKey, publicKey, issuer, kid);
        }
        return PtoToken.hmac(hmacSecret, issuer);
    }
}
