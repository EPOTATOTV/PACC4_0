package com.potatotv.paccclient.transport;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * WSS 会话级动态密钥（客户端侧）。
 *
 * <p>与服务端 {@code WssSessionKeys} 的派生规则必须逐字节一致——任一侧改动都会让全线验签失败，
 * 因此两边各有一份单测钉住同样的输入→输出（见 {@code WssSessionKeyTest} 与服务端
 * {@code WssSessionKeysTest}，二者的期望值相同）。</p>
 *
 * <pre>
 *   sessionKey(0) = HMAC-SHA256(key = staticSecret, data = "pacc-wss-session-v1|" + sessionId + "|" + saltHex)
 *   sessionKey(n) = HMAC-SHA256(key = sessionKey(n-1), data = "pacc-wss-rotate-v1|" + sessionId + "|" + n)
 * </pre>
 *
 * <p>生命周期：{@link #start()} 生成会话 ID 与盐 → 用静态密钥发 session_init →
 * 收到 session_ready 后 {@link #activate()} 切到会话密钥 → 累计到阈值 {@link #rotate()} 轮换。
 * 连接断开时 {@link #reset()}：重连不复用旧密钥，重新协商。</p>
 */
public final class WssSessionKey {

    /** 与服务端一致的签名版本与消息类型。 */
    public static final int SIG_V1 = 1;
    public static final int SIG_V2 = 2;
    public static final String INIT_TYPE = "session_init";
    public static final String READY_TYPE = "session_ready";
    public static final String REKEY_TYPE = "session_rekey";
    public static final String ACK_TYPE = "session_ack";

    /** 轮换阈值：每发 512 条或每 15 分钟轮换一次，取先到者。 */
    public static final int ROTATE_AFTER_MESSAGES = 512;
    public static final long ROTATE_AFTER_MILLIS = 15 * 60_000L;

    private static final SecureRandom RAND = new SecureRandom();

    private final String staticSecret;
    private String sessionId;
    private String saltHex;
    private String keyHex;
    private long epoch;
    private boolean active;
    private int messagesSinceRotate;
    private long lastRotateMs;

    public WssSessionKey(String staticSecret) {
        this.staticSecret = staticSecret == null ? "" : staticSecret;
    }

    /** 会话密钥是否已激活（未激活时出站仍用静态密钥 v1，保证握手期间不中断）。 */
    public boolean active() {
        return active;
    }

    public String sessionId() {
        return sessionId;
    }

    public long epoch() {
        return epoch;
    }

    /** 出站信封应使用的签名版本。 */
    public int sigVersion() {
        return active ? SIG_V2 : SIG_V1;
    }

    /** 出站信封应使用的签名密钥。 */
    public String signingKey() {
        return active ? keyHex : staticSecret;
    }

    /** 生成新的会话 ID 与盐，返回 session_init 的负载（尚未激活，仍需服务端回 ready）。 */
    public String start() {
        byte[] sid = new byte[16];
        byte[] salt = new byte[32];
        RAND.nextBytes(sid);
        RAND.nextBytes(salt);
        this.sessionId = HexFormat.of().formatHex(sid);
        this.saltHex = HexFormat.of().formatHex(salt);
        this.epoch = 0;
        this.active = false;
        this.keyHex = null;
        this.messagesSinceRotate = 0;
        return "{\"salt\":\"" + saltHex + "\"}";
    }

    /** 收到 session_ready：按本端保存的盐派生会话密钥并激活。 */
    public boolean activate() {
        if (sessionId == null || saltHex == null) return false;
        this.keyHex = deriveSessionKey(staticSecret, sessionId, saltHex);
        this.epoch = 0;
        this.active = true;
        this.messagesSinceRotate = 0;
        this.lastRotateMs = System.currentTimeMillis();
        return true;
    }

    /** 断线时调用：丢弃本会话密钥，重连将重新协商（不复用旧密钥）。 */
    public void reset() {
        this.active = false;
        this.keyHex = null;
        this.sessionId = null;
        this.saltHex = null;
        this.epoch = 0;
        this.messagesSinceRotate = 0;
    }

    /**
     * 记录一条已发出的信封；到达阈值时返回需要轮换的 epoch（调用方据此发 session_rekey），
     * 不需要轮换返回 -1。
     */
    public long dueForRotation() {
        if (!active) return -1;
        messagesSinceRotate++;
        long now = System.currentTimeMillis();
        if (messagesSinceRotate >= ROTATE_AFTER_MESSAGES
                || now - lastRotateMs >= ROTATE_AFTER_MILLIS) {
            return epoch;
        }
        return -1;
    }

    /**
     * 收到 session_ack：推进到下一 epoch。
     * <p>只接受「正好是当前 epoch + 1」的确认，防止乱序 ack 把密钥推到服务端不认的位置。</p>
     */
    public boolean commitRotation(long ackedEpoch) {
        if (!active || ackedEpoch != epoch + 1) return false;
        this.keyHex = deriveNextKey(keyHex, sessionId, ackedEpoch);
        this.epoch = ackedEpoch;
        this.messagesSinceRotate = 0;
        this.lastRotateMs = System.currentTimeMillis();
        return true;
    }

    /** 轮换请求的负载。 */
    public String rekeyPayload() {
        return "{\"epoch\":" + epoch + "}";
    }

    // -------------------------------- 派生（与服务端逐字节一致） --------------------------------

    public static String deriveSessionKey(String staticSecret, String sessionId, String saltHex) {
        return hmacHex(staticSecret == null ? "" : staticSecret,
                "pacc-wss-session-v1|" + sessionId + "|" + saltHex);
    }

    public static String deriveNextKey(String currentKeyHex, String sessionId, long epoch) {
        return hmacHex(currentKeyHex, "pacc-wss-rotate-v1|" + sessionId + "|" + epoch);
    }

    private static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("会话密钥派生失败", e);
        }
    }
}
