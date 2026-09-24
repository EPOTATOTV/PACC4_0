package com.potatotv.paccclient.transport;

import com.potatotv.pacc.proto.PaccWire;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.MessageDigest;

/**
 * 客户端 WSS 信封（protobuf {@link PaccWire.WsEnvelope}）构建与签名。
 * <p>与服务端 {@code PaccWireCodec} 同构：规范字段 HMAC-SHA256、时间戳、nonce 防重放。
 * 用于客户端→服务端的查端信令（inspect_*）以二进制帧收发，服务端校验一致。</p>
 *
 * <p>签名密钥按 {@link WssSessionKey#sigVersion()} 选择：未激活时用静态密钥（v1，握手期），
 * 激活后与会话密钥（v2）看齐。轮换归 {@link WssSessionKey} 管理，本类只负责「用当前密钥签名」。</p>
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
    public PaccWire.WsEnvelope build(String type, String sessionId, String payloadJson) {
        PaccWire.WsEnvelope.Builder b = base(type, sessionId, payloadJson);
        String sig = hmacHex(canonical(b.build()), secret);
        return b.setSignature(sig).build();
    }

    private PaccWire.WsEnvelope.Builder base(String type, String sessionId, String payloadJson) {
        long ts = System.currentTimeMillis();
        byte[] nb = new byte[8];
        RAND.nextBytes(nb);
        String nonce = hex(nb);
        PaccWire.WsEnvelope.Builder b = PaccWire.WsEnvelope.newBuilder()
                .setType(type == null ? "" : type)
                .setTsMs(ts)
                .setNonce(nonce)
                .setPteid(pteid)
                .setPayloadJson(payloadJson == null ? "" : payloadJson)
                .setSigVersion(sigVersion);
        if (sessionId != null) b.setSessionId(sessionId);
        return b;
    }

    private static String canonical(PaccWire.WsEnvelope env) {
        return env.getType() + "|" + env.getTsMs() + "|" + env.getNonce()
                + "|" + env.getSessionId() + "|" + env.getPteid() + "|" + env.getPayloadJson();
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名失败", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    /** 供测试/校验复算。 */
    public boolean signatureMatches(PaccWire.WsEnvelope env) {
        if (env == null) return false;
        String expected = hmacHex(canonical(env), secret);
        byte[] a = env.getSignature().getBytes(StandardCharsets.UTF_8);
        byte[] e = expected.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, e);
    }
}