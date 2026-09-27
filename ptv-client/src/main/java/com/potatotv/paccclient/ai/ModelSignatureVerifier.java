package com.potatotv.paccclient.ai;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * 模型签名校验（文档 §2.1.3「每个模型文件带 PTV 私钥签名，客户端验证后加载」）。
 *
 * <p>公钥来自环境变量 {@code PACC_MODEL_PUBKEY}（Base64 编码的 X.509 DER 公钥，支持 RSA / EC）。
 * 未配置时按“未配置”处理：{@link #verify} 返回 {@code true}，仅依赖 {@link PaccModelFormat} 的
 * SHA-256 完整性校验，且不产生任何日志输出；配置后签名不合法一律拒绝（返回 {@code false}）。</p>
 */
public final class ModelSignatureVerifier {

    public static final String ENV_PUBKEY = "PACC_MODEL_PUBKEY";

    private ModelSignatureVerifier() {
    }

    /** 是否已通过环境变量配置校验公钥。 */
    public static boolean configured() {
        String v = System.getenv(ENV_PUBKEY);
        return v != null && !v.isBlank();
    }

    /**
     * 校验模型字节上的签名。
     * <p>未配置公钥 → {@code true}（不校验，仅靠 SHA-256）；已配置但公钥非法、签名缺失或不匹配 → {@code false}。</p>
     */
    public static boolean verify(byte[] model, byte[] signature) {
        String b64 = System.getenv(ENV_PUBKEY);
        if (b64 == null || b64.isBlank()) return true; // 未配置：log-free，仅依赖 SHA256
        if (model == null || signature == null) return false;
        try {
            PublicKey key = parseKey(b64);
            if (key == null) return false;
            String alg = "RSA".equalsIgnoreCase(key.getAlgorithm()) ? "SHA256withRSA" : "SHA256withECDSA";
            Signature sig = Signature.getInstance(alg);
            sig.initVerify(key);
            sig.update(model);
            return sig.verify(signature);
        } catch (Exception e) {
            return false;
        }
    }

    /** 依次尝试 RSA / EC 解析 X.509 公钥；均失败返回 null。 */
    private static PublicKey parseKey(String b64) {
        byte[] der;
        try {
            der = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        for (String algo : new String[]{"RSA", "EC"}) {
            try {
                return KeyFactory.getInstance(algo).generatePublic(spec);
            } catch (Exception ignored) {
                // 换下一个算法尝试
            }
        }
        return null;
    }
}