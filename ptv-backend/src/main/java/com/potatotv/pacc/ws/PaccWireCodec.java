package com.potatotv.pacc.ws;

import com.potatotv.pbp.PbpCrypto;
import com.potatotv.pbp.gen.PaccEnvelope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WSS 信封（PBP {@link PaccEnvelope}）编解码与加验核。
 * <p>这是二进制协议替换 protobuf 后的落地形态：查端信令经 PBP 帧收发，签名从
 * 「规范化字符串」改为帧尾 32 字节二进制 HMAC-SHA256，覆盖面是「帧头 + 载荷」整体
 * （见 {@code PbpFrame#signingInput}），所以 type/时间戳/nonce/session/pteid/payload
 * 任一被改写都会验签失败。时间戳窗口与 nonce 防重放逻辑不变。</p>
 *
 * <p>签名密钥分两级：{@code sig_version=1} 用长期静态密钥（兼容旧客户端），
 * {@code sig_version=2} 用 {@link WssSessionKeys} 派生的会话密钥。
 * 当 {@code pacc.wss.session-key-required=true} 时只接受 v2，v1 一律拒绝。</p>
 *
 * <p><b>密钥的字节形态必须与客户端逐字节一致</b>：是「十六进制字符串的 UTF-8 字节」，
 * 不是把 hex 解码成 32 字节。改成解码等于两端密钥不一致，全部信封会静默验签失败。</p>
 */
@Component
public class PaccWireCodec {

    private final String secret;
    private final long tsWindowMs;
    private final WssSessionKeys sessionKeys;

    /** nonce 去重：短窗口内已见即视为重放（进程内；多实例换 Redis/共享）。 */
    private final ConcurrentHashMap<String, Long> seenNonce = new ConcurrentHashMap<>();

    public PaccWireCodec(@Value("${pacc.security.wss-sign-secret}") String secret,
                         @Value("${pacc.wss.ts-window-ms:60000}") long tsWindowMs,
                         WssSessionKeys sessionKeys) {
        this.secret = secret == null ? "" : secret;
        this.tsWindowMs = tsWindowMs;
        this.sessionKeys = sessionKeys;
    }

    /** 静态签名密钥（供 session_init 的引导验签使用）。 */
    public String staticSecret() {
        return secret;
    }

    /** 构建并签名一枚信封（静态密钥，v1）。 */
    public PaccEnvelope build(String type, String sessionId, String pteid, String payloadJson) {
        return build(type, sessionId, pteid, payloadJson, secret);
    }

    /** 用会话密钥构建一枚 v2 信封（服务端回 session_ready / session_ack 时使用）。 */
    public PaccEnvelope buildWithSessionKey(String type, String sessionId, String pteid,
                                            String payloadJson, String sessionKeyHex) {
        return sign(newEnvelope(type, sessionId, pteid, payloadJson, WssSessionKeys.SIG_V2), sessionKeyHex);
    }

    /** 可加参的构建（测试用）。 */
    public static PaccEnvelope build(String type, String sessionId, String pteid,
                                     String payloadJson, String secret) {
        return sign(newEnvelope(type, sessionId, pteid, payloadJson, WssSessionKeys.SIG_V1), secret);
    }

    private static PaccEnvelope.Builder newEnvelope(String type, String sessionId, String pteid,
                                                    String payloadJson, int sigVersion) {
        long ts = System.currentTimeMillis();
        PaccEnvelope.Builder b = PaccEnvelope.newBuilder()
                .setType(type == null ? "" : type)
                .setTsMs(ts)
                .setNonce(nonce(ts))
                .setPteid(pteid == null ? "" : pteid)
                .setPayloadJson(payloadJson == null ? "" : payloadJson)
                .setSigVersion(sigVersion);
        if (sessionId != null) b.setSessionId(sessionId);
        return b;
    }

    /**
     * 先构出待签字节、再把 32 字节签名挂回 Builder。
     * <p>待签字节是「帧头 + 载荷」而非仅载荷：帧头里有 messageId 与时间戳，
     * 只盖载荷等于允许中间人改写元数据。</p>
     */
    private static PaccEnvelope sign(PaccEnvelope.Builder b, String secret) {
        byte[] sig = PbpCrypto.hmacSha256(secret.getBytes(StandardCharsets.UTF_8), b.build().signingInput());
        return b.setSignatureBytes(sig).build();
    }

    /** 与 {@link #verify} 组合使用：解析二进制帧为信封。 */
    public PaccEnvelope parse(byte[] bytes) {
        return PaccEnvelope.parseFrom(bytes);
    }

    /**
     * 校验信封：签名匹配 + 时间戳在窗口内 + nonce 未重放。
     * <p>签名密钥按 {@code sig_version} 选择；强制模式下 v1 直接判非法。</p>
     * 返回 false 表示非法，调用方应丢弃/断开。
     */
    public boolean verify(PaccEnvelope env) {
        if (env == null) return false;

        String keyHex;
        if (env.getSigVersion() >= WssSessionKeys.SIG_V2) {
            WssSessionKeys.Session s = sessionKeys.get(env.getSessionId());
            // 会话不存在（未握手 / 已被断开清理 / 已过期）→ 无法验签，拒绝
            if (s == null) return false;
            // 会话与玩家绑定：拿别人的 sessionId 签名不算数
            if (!s.pteid().isEmpty() && !s.pteid().equals(env.getPteid())) return false;
            keyHex = s.keyHex();
        } else {
            if (sessionKeys.required()) return false;
            keyHex = secret;
        }

        byte[] expected = PbpCrypto.hmacSha256(keyHex.getBytes(StandardCharsets.UTF_8), env.signingInput());
        if (!PbpCrypto.constantTimeEquals(expected, env.signatureBytes())) return false;
        long now = System.currentTimeMillis();
        if (Math.abs(now - env.getTsMs()) > tsWindowMs) return false;
        return seenNonce.putIfAbsent(env.getNonce(), env.getTsMs()) == null;
    }

    private static String nonce(long tsMs) {
        return Long.toHexString(tsMs) + "-" + Long.toHexString(System.nanoTime());
    }
}