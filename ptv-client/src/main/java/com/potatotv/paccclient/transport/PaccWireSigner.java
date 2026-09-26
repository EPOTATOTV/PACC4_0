package com.potatotv.paccclient.transport;

import com.potatotv.pbp.PbpCrypto;
import com.potatotv.pbp.gen.PaccEnvelope;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * 客户端 WSS 信封（PBP {@link PaccEnvelope}）构建与签名。
 * <p>与服务端 {@code PaccWireCodec} 同构：签名是覆盖「帧头 + 载荷」的 HMAC-SHA256，
 * 时间戳窗口与 nonce 防重放由服务端校验。与旧 protobuf 实现的区别在于签名不再是
 * 规范化字符串、而是整帧字节，字段任何一处被改写都会验签失败。</p>
 *
 * <p>签名密钥按 {@link WssSessionKey#sigVersion()} 选择：未激活时用静态密钥（v1，握手期），
 * 激活后与会话密钥（v2）看齐。轮换归 {@link WssSessionKey} 管理，本类只负责「用当前密钥签名」。</p>
 *
 * <p><b>密钥的字节形态是「十六进制字符串的 UTF-8 字节」，不是把 hex 解码成 32 字节</b>：
 * 服务端用同一形态参与 HMAC，改成解码等于两端密钥不一致，全部信封验签失败。</p>
 */
public final class PaccWireSigner {

    private volatile String secret = "";
    private volatile int sigVersion = WssSessionKey.SIG_V1;
    private final String pteid;
    private static final SecureRandom RAND = new SecureRandom();

    public PaccWireSigner(String secret, String pteid) {
        this.secret = secret == null ? "" : secret;
        this.pteid = pteid == null ? "" : pteid;
    }

    /** 切换当前签名密钥与版本（会话密钥激活/轮换时调用）。 */
    public void setSecret(String newSecret, int newSigVersion) {
        this.secret = newSecret == null ? "" : newSecret;
        this.sigVersion = newSigVersion;
    }

    /** 构建并签名一枚信封。 */
    public PaccEnvelope build(String type, String sessionId, String payloadJson) {
        PaccEnvelope.Builder b = base(type, sessionId, payloadJson);
        byte[] sig = PbpCrypto.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), b.build().signingInput());
        return b.setSignatureBytes(sig).build();
    }

    private PaccEnvelope.Builder base(String type, String sessionId, String payloadJson) {
        long ts = System.currentTimeMillis();
        byte[] nb = new byte[8];
        RAND.nextBytes(nb);
        String nonce = hex(nb);
        PaccEnvelope.Builder b = PaccEnvelope.newBuilder()
                .setType(type == null ? "" : type)
                .setTsMs(ts)
                .setNonce(nonce)
                .setPteid(pteid)
                .setPayloadJson(payloadJson == null ? "" : payloadJson)
                .setSigVersion(sigVersion);
        if (sessionId != null) b.setSessionId(sessionId);
        return b;
    }

    /** 供测试/校验复算。 */
    public boolean signatureMatches(PaccEnvelope env) {
        if (env == null) return false;
        byte[] expected = PbpCrypto.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), env.signingInput());
        return PbpCrypto.constantTimeEquals(expected, env.signatureBytes());
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }
}