package com.potatotv.pacc.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WSS 会话级动态密钥：派生、轮换与生命周期管理。
 *
 * <p>要解决的问题：原先每条信封都用同一个长期静态密钥签名。这个密钥一旦通过内存抓取、
 * 日志或客户端逆向泄露，攻击者就能伪造任意玩家的任意消息，且事后无法区分是哪条会话泄的。
 * 会话密钥把静态密钥的使用收敛到「每连接一次握手」，之后每会话一把独立密钥，并按 epoch 链式轮换。</p>
 *
 * <p>派生规则（与客户端 {@code WssSessionKey} 必须逐字节一致，任一侧改动都会导致全线验签失败）：
 * <pre>
 *   sessionKey(0) = HMAC-SHA256(key = staticSecret, data = "pacc-wss-session-v1|" + sessionId + "|" + saltHex)
 *   sessionKey(n) = HMAC-SHA256(key = sessionKey(n-1), data = "pacc-wss-rotate-v1|" + sessionId + "|" + n)
 * </pre>
 * 密钥一律以 64 位小写十六进制字符串表示，避免两侧对字节序/编码的理解出现分歧。
 * 轮换用链式（上一把当 HMAC 密钥）而非重新从静态密钥派生：这样即使某一把会话密钥泄露，
 * 也推不出静态密钥，更推不出后续 epoch 的密钥（HMAC 单向）。</p>
 *
 * <p>为什么不是 ECDH：那需要双向协商与协议扩展，而本链路的对端是自家客户端，
 * 双方本就共享静态密钥，用 HMAC-KDF 就能拿到「前向隔离 + 可轮换」的收益，复杂度低一个量级。</p>
 */
@Component
public class WssSessionKeys {

    private static final Logger log = LoggerFactory.getLogger(WssSessionKeys.class);

    /** 签名版本：1 = 静态密钥（兼容旧客户端），2 = 会话密钥。 */
    public static final int SIG_V1 = 1;
    public static final int SIG_V2 = 2;

    public static final String INIT_TYPE = "session_init";
    public static final String READY_TYPE = "session_ready";
    public static final String REKEY_TYPE = "session_rekey";
    public static final String ACK_TYPE = "session_ack";

    /** 会话状态存活上限：超过则视为僵尸会话清掉（客户端断线不会发通知，只能靠超时兜底）。 */
    private static final long SESSION_TTL_MS = 30 * 60_000L;
    private static final int MAX_SESSIONS = 20_000;

    private final SecureRandom random = new SecureRandom();

    /** 是否强制要求会话密钥（true 时拒绝一切 sig_version < 2 的信封，旧客户端将无法通信）。 */
    private final boolean required;

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    public WssSessionKeys(@Value("${pacc.wss.session-key-required:false}") boolean required) {
        this.required = required;
        if (required) {
            log.info("WSS 会话密钥强制模式已开启：仅接受 sig_version>=2 的信封");
        }
    }

    public boolean required() {
        return required;
    }

    /** 一条会话的动态密钥状态。 */
    public record Session(String pteid, String keyHex, long epoch, long lastSeenMs) {}

    // -------------------------------- 派生（纯函数，两侧共用） --------------------------------

    /** 由静态密钥 + 会话 ID + 盐派生 epoch 0 的会话密钥。 */
    public static String deriveSessionKey(String staticSecret, String sessionId, String saltHex) {
        return hmacHex(staticSecret == null ? "" : staticSecret,
                "pacc-wss-session-v1|" + sessionId + "|" + saltHex);
    }

    /** 链式轮换：以上一把会话密钥为 HMAC 密钥派生下一把。 */
    public static String deriveNextKey(String currentKeyHex, String sessionId, long epoch) {
        return hmacHex(currentKeyHex, "pacc-wss-rotate-v1|" + sessionId + "|" + epoch);
    }

    /** 生成 32 字节随机盐（十六进制）。 */
    public String newSalt() {
        byte[] b = new byte[32];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    // -------------------------------- 会话注册与轮换 --------------------------------

    /**
     * 注册（或按同一 sessionId 幂等重建）一条会话。
     *
     * @return 该会话 epoch 0 的密钥；sessionId 或盐非法返回 null。
     */
    public Session open(String sessionId, String saltHex, String pteid, String staticSecret) {
        if (sessionId == null || sessionId.isBlank() || saltHex == null || saltHex.isBlank()) {
            return null;
        }
        // 只接受十六进制盐，避免把任意串当盐用（也挡住把密钥塞进盐里的试探）
        if (!saltHex.matches("[0-9a-fA-F]{16,128}")) {
            return null;
        }
        evictStale();
        if (sessions.size() >= MAX_SESSIONS) {
            log.warn("WSS 会话数达上限 {}，拒绝新会话", MAX_SESSIONS);
            return null;
        }
        Session s = new Session(pteid == null ? "" : pteid,
                deriveSessionKey(staticSecret, sessionId, saltHex.toLowerCase()),
                0L, System.currentTimeMillis());
        sessions.put(sessionId, s);
        return s;
    }

    /** 取会话（并刷新活跃时间）。不存在返回 null。 */
    public Session get(String sessionId) {
        if (sessionId == null) return null;
        Session s = sessions.get(sessionId);
        if (s == null) return null;
        Session fresh = new Session(s.pteid(), s.keyHex(), s.epoch(), System.currentTimeMillis());
        sessions.put(sessionId, fresh);
        return fresh;
    }

    /**
     * 轮换到下一 epoch。
     *
     * @param expectedEpoch 调用方声明的当前 epoch，必须与已存 epoch 一致，防止重放旧 epoch 的 rekey 回退密钥
     * @return 轮换后的会话；epoch 不匹配或会话不存在返回 null
     */
    public Session rotate(String sessionId, long expectedEpoch) {
        Session s = get(sessionId);
        if (s == null || s.epoch() != expectedEpoch) {
            return null;
        }
        long next = s.epoch() + 1;
        Session rotated = new Session(s.pteid(), deriveNextKey(s.keyHex(), sessionId, next), next,
                System.currentTimeMillis());
        sessions.put(sessionId, rotated);
        return rotated;
    }

    /** 断开时立即销毁会话密钥（不留在内存里等 TTL）。 */
    public void close(String sessionId) {
        if (sessionId != null) sessions.remove(sessionId);
    }

    /** 惰性清理：会话只增不减会随重连次数无限堆积。 */
    private void evictStale() {
        if (sessions.size() <= MAX_SESSIONS / 2) return;
        long threshold = System.currentTimeMillis() - SESSION_TTL_MS;
        sessions.entrySet().removeIf(e -> e.getValue().lastSeenMs() < threshold);
    }

    // -------------------------------- 工具 --------------------------------

    static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("会话密钥派生失败", e);
        }
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
