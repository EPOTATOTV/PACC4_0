package com.potatotv.pacc.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 玩家端 WSS 消息完整性校验（HMAC-SHA256 + 时间窗 + nonce 防重放）。
 * <p>与玩家端上报器使用同一密钥，签名覆盖
 * {@code pteid + "." + ts + "." + nonce + "." + 去 sig 后的规范化载荷}，
 * 可识别抓包重放与载荷篡改。未配置密钥（blank）时直接放行（兼容未启用签名的客户端）。</p>
 */
@Component
public class WssMessageGuard {

    private static final long WINDOW_SECONDS = 300;
    private static final int NONCE_CACHE_MAX = 4096;

    private final ObjectMapper mapper;
    private final String secret;
    private final ConcurrentMap<String, Long> nonces = new ConcurrentHashMap<>();

    public WssMessageGuard(ObjectMapper mapper,
                           @Value("${pacc.security.wss-sign-secret:}") String secret) {
        this.mapper = mapper;
        this.secret = secret == null ? "" : secret;
    }

    /** 完整性校验是否已启用（配置了密钥）。 */
    public boolean enabled() {
        return !secret.isBlank();
    }

    /**
     * 校验一条玩家端消息。
     *
     * @return 合法返回 true；非法（缺签名 / 签名不符 / 超时 / nonce 重放）返回 false。
     */
    public boolean verify(@NonNull String pteid, @NonNull JsonNode node) {
        if (secret.isBlank()) return true;
        // 1) 时间窗
        long ts = node.path("ts").asLong(0);
        if (ts <= 0 || Math.abs(System.currentTimeMillis() / 1000 - ts) > WINDOW_SECONDS) {
            return false;
        }
        // 2) 重放：同一 nonce 只能使用一次
        String nonce = node.path("nonce").asText("");
        if (nonce.isBlank()) return false;
        Long prev = nonces.putIfAbsent(nonce, ts);
        if (prev != null) return false; // 该 nonce 已被使用过
        evict(ts);
        // 3) 签名复算
        String sig = node.path("sig").asText("");
        if (sig.isBlank()) {
            nonces.remove(nonce);
            return false;
        }
        String canonical = canonicalWithoutSig(node);
        if (canonical == null) {
            nonces.remove(nonce);
            return false;
        }
        String expected = hmacHex(secret, pteid + "." + ts + "." + nonce + "." + canonical);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sig.getBytes(StandardCharsets.UTF_8))) {
            nonces.remove(nonce); // 签名不符，移除 nonce；若携带正确签名可重试
            return false;
        }
        return true;
    }

    /** 去除 sig 字段后，按接收到的键序重现规范化 JSON（与客户端 Json.encode 同构）。 */
    private String canonicalWithoutSig(JsonNode node) {
        try {
            if (!(node instanceof ObjectNode obj)) return null;
            ObjectNode copy = obj.deepCopy();
            copy.remove("sig");
            return mapper.writeValueAsString(copy);
        } catch (Exception e) {
            return null;
        }
    }

    /** 惰性清理过期 nonce，控制缓存上限。 */
    private void evict(long now) {
        if (nonces.size() <= NONCE_CACHE_MAX) return;
        long threshold = now - WINDOW_SECONDS;
        nonces.entrySet().removeIf(e -> e.getValue() < threshold);
    }

    private static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}