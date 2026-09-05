package com.potatotv.pacc.ws;

import com.potatotv.pacc.proto.PaccWire;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WSS 信封（protobuf {@link PaccWire.WsEnvelope}）编解码与加验核。
 * <p>这是 protobuf 统一的第一层落地：查端信令经二进制帧收发时以此信封承载，含
 * HMAC-SHA256 签名、时间戳窗口、nonce 防重放。与 JSON 文本帧方案并存（Increment 2 再接入
 * PlayerWebSocketHandler 分派），本类为增量模块，不影响现有 JSON 链路。</p>
 */
@Component
public class PaccWireCodec {

    private final String secret;
    private final long tsWindowMs;

    /** nonce 去重：短窗口内已见即视为重放（进程内；多实例换 Redis/共享）。 */
    private final ConcurrentHashMap<String, Long> seenNonce = new ConcurrentHashMap<>();

    public PaccWireCodec(@Value("${pacc.security.wss-sign-secret}") String secret,
                         @Value("${pacc.wss.ts-window-ms:60000}") long tsWindowMs) {
        this.secret = secret == null ? "" : secret;
        this.tsWindowMs = tsWindowMs;
    }

    /** 构建并签名一枚信封。 */
    public PaccWire.WsEnvelope build(String type, String sessionId, String pteid, String payloadJson) {
        return build(type, sessionId, pteid, payloadJson, secret);
    }

    /** 可加参的构建（测试用）。 */
    public static PaccWire.WsEnvelope build(String type, String sessionId, String pteid,
                                            String payloadJson, String secret) {
        long ts = System.currentTimeMillis();
        String nonce = nonce(ts);
        PaccWire.WsEnvelope.Builder b = PaccWire.WsEnvelope.newBuilder()
                .setType(type == null ? "" : type)
                .setTsMs(ts)
                .setNonce(nonce)
                .setPteid(pteid == null ? "" : pteid)
                .setPayloadJson(payloadJson == null ? "" : payloadJson)
                .setSigVersion(1);
        if (sessionId != null) b.setSessionId(sessionId);
        String sig = hmac(canonical(b.build()), secret);
        return b.setSignature(sig).build();
    }

    /** 与 {@link #verify} 组合使用：解析二进制帧为信封。 */
    public PaccWire.WsEnvelope parse(byte[] bytes) throws com.google.protobuf.InvalidProtocolBufferException {
        return PaccWire.WsEnvelope.parseFrom(bytes);
    }

    /**
     * 校验信封：签名匹配 + 时间戳在窗口内 + nonce 未重放。
     * 返回 false 表示非法，调用方应丢弃/断开。
     */
    public boolean verify(PaccWire.WsEnvelope env) {
        if (env == null) return false;
        String expected = hmac(canonical(env), secret);
        if (!constantTimeEquals(env.getSignature(), expected)) return false;
        long now = System.currentTimeMillis();
        if (Math.abs(now - env.getTsMs()) > tsWindowMs) return false;
        return seenNonce.putIfAbsent(env.getNonce(), env.getTsMs()) == null;
    }

    /** 规范化待签字段（type|ts|nonce|session|pteid|payload）——payload 必须参与签名，否则可被换包）。 */
    private static String canonical(PaccWire.WsEnvelope env) {
        return env.getType() + "|" + env.getTsMs() + "|" + env.getNonce()
                + "|" + env.getSessionId() + "|" + env.getPteid() + "|" + env.getPayloadJson();
    }

    private static String nonce(long tsMs) {
        return Long.toHexString(tsMs) + "-" + Long.toHexString(System.nanoTime());
    }

    private static String hmac(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        byte[] x = a.getBytes(StandardCharsets.UTF_8);
        byte[] y = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(x, y);
    }
}