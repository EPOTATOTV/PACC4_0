package com.potatotv.pacc.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端验证玩家端签名算法与服务器复算在 JSON 转义 / 键序上完全一致，
 * 并验证防重放（同一 nonce  reused 被拒）与篡改识别。
 */
class WssMessageGuardTest {

    private static final String SECRET = "pacc-dev-wss-sign-key-change-me";
    private static final String PTEID = "PT0000000001";

    /** 客户端式 JSON 编码（与 ptv-client Json.encode 保持一致的紧凑、保序编码）。 */
    private static String encodeString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String encodeMap(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append(encodeString(e.getKey())).append(':');
            Object v = e.getValue();
            if (v instanceof String s) sb.append(encodeString(s));
            else if (v instanceof Number || v instanceof Boolean) sb.append(v);
            else if (v == null) sb.append("null");
            else sb.append(encodeString(String.valueOf(v)));
        }
        return sb.append('}').toString();
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 完全复刻玩家端 WssReporter.sign 逻辑，生成待发送的签名消息。 */
    private static String clientSign(LinkedHashMap<String, Object> payload, long ts, String nonce) {
        payload.put("ts", ts);
        payload.put("nonce", nonce);
        String canonical = encodeMap(payload);
        String sig = hmacHex(SECRET, PTEID + "." + ts + "." + nonce + "." + canonical);
        payload.put("sig", sig);
        return encodeMap(payload);
    }

    private static LinkedHashMap<String, Object> samplePayload() {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        m.put("type", "event");
        m.put("event_type", "aimbot");
        m.put("severity", "high");
        m.put("client_risk_score", 82L);
        m.put("process_name", "javaw.exe");
        m.put("memory_region", null);
        m.put("signature_hit", "demo-sig");
        m.put("os_info", "win10_x64");
        m.put("client_version", "v4.0.0");
        m.put("detail", "端侧检测: aimbot | 进程=javaw.exe\n含中文与\"引号\"");
        return m;
    }

    @Test
    void validSignedMessageAccepted() throws Exception {
        WssMessageGuard guard = new WssMessageGuard(new ObjectMapper(), SECRET);
        long ts = System.currentTimeMillis() / 1000;
        long randomNonce = Math.abs(java.util.concurrent.ThreadLocalRandom.current().nextLong());
        String nonce = String.format("%016x", randomNonce);
        String wire = clientSign(samplePayload(), ts, nonce);
        JsonNode node = new ObjectMapper().readTree(wire);
        assertTrue(guard.verify(PTEID, node));
    }

    @Test
    void replayedNonceRejected() throws Exception {
        WssMessageGuard guard = new WssMessageGuard(new ObjectMapper(), SECRET);
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "deadbeefdeadbeef";
        String wire = clientSign(samplePayload(), ts, nonce);
        JsonNode node = new ObjectMapper().readTree(wire);
        assertTrue(guard.verify(PTEID, node));
        // 原样重放同一条消息 → 同一 nonce 已被使用 → 拒绝
        assertFalse(guard.verify(PTEID, new ObjectMapper().readTree(wire)));
    }

    @Test
    void tamperedPayloadRejected() throws Exception {
        WssMessageGuard guard = new WssMessageGuard(new ObjectMapper(), SECRET);
        long ts = System.currentTimeMillis() / 1000;
        String nonce = "cafebabecafebabe";
        String wire = clientSign(samplePayload(), ts, nonce);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode node = (ObjectNode) mapper.readTree(wire);
        // 篡改 client_risk_score 而不重签 → 签名复算不符 → 拒绝
        node.put("client_risk_score", 99);
        assertFalse(guard.verify(PTEID, node));
    }

    @Test
    void staleTimestampRejected() throws Exception {
        WssMessageGuard guard = new WssMessageGuard(new ObjectMapper(), SECRET);
        String nonce = "abababababababab";
        // 10 分钟旧时间戳，超出 5 分钟窗口
        long ts = System.currentTimeMillis() / 1000 - 600;
        String wire = clientSign(samplePayload(), ts, nonce);
        JsonNode node = new ObjectMapper().readTree(wire);
        assertFalse(guard.verify(PTEID, node));
    }

    @Test
    void blankSecretDisablesEnforcement() throws Exception {
        WssMessageGuard guard = new WssMessageGuard(new ObjectMapper(), "");
        assertFalse(guard.enabled());
        JsonNode node = new ObjectMapper().readTree("{\"type\":\"ping\"}");
        assertTrue(guard.verify(PTEID, node));
    }
}