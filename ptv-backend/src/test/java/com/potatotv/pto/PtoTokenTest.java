package com.potatotv.pto;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PTO 核心行为测试：签发/校验往返、篡改拒绝、算法混淆拒绝、issuer/时间窗约束、
 * 以及 jjwt 旧令牌的兼容性（同一 HMAC-SHA256 口径）。
 */
class PtoTokenTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final String ISSUER = "pacc-ptv";

    private final PtoToken pto = new PtoToken(KEY, ISSUER);

    @Test
    void roundTripClaims() {
        String token = pto.builder()
                .audience("pacc-client")
                .subject("PT123456")
                .claim("role", "player")
                .expiresAt(Instant.now().plusSeconds(60))
                .sign();

        PtoClaims claims = pto.verify(token);
        assertEquals("PT123456", claims.subject());
        assertEquals(ISSUER, claims.issuer());
        assertEquals("player", claims.getString("role"));
        assertTrue(claims.audience().contains("pacc-client"));
    }

    @Test
    void tamperedPayloadRejected() {
        String token = pto.builder().subject("PT1").expiresAt(Instant.now().plusSeconds(60)).sign();
        String[] parts = token.split("\\.");
        // 改一个字符就会破坏签名
        String tampered = parts[0] + "." + parts[1].substring(0, parts[1].length() - 1) + "A" + "." + parts[2];
        assertThrows(PtoException.class, () -> pto.verify(tampered));
    }

    @Test
    void wrongKeyRejected() {
        String token = pto.builder().subject("PT1").expiresAt(Instant.now().plusSeconds(60)).sign();
        PtoToken other = new PtoToken("ffffffffffffffffffffffffffffffff".getBytes(StandardCharsets.UTF_8), ISSUER);
        assertThrows(PtoException.class, () -> other.verify(token));
    }

    @Test
    void algNoneRejected() {
        // 手工拼一个 alg:none 的令牌：签名段留空。PTO 只认 HS256，必须拒绝。
        String header = base64Url("{\"alg\":\"none\",\"typ\":\"JWT\"}");
        String payload = base64Url("{\"iss\":\"" + ISSUER + "\",\"exp\":9999999999}");
        assertThrows(PtoException.class, () -> pto.verify(header + "." + payload + "."));
    }

    @Test
    void issuerMismatchRejected() {
        String token = pto.builder().subject("PT1").expiresAt(Instant.now().plusSeconds(60)).sign();
        assertThrows(PtoException.class, () -> pto.verify(token, "someone-else"));
    }

    @Test
    void expiredRejected() {
        String token = pto.builder().subject("PT1").expiresAt(Instant.now().minusSeconds(5)).sign();
        assertThrows(PtoException.class, () -> pto.verify(token));
    }

    @Test
    void missingExpRejected() {
        String token = pto.builder().subject("PT1").sign();
        assertThrows(PtoException.class, () -> pto.verify(token));
    }

    @Test
    void garbageRejected() {
        assertThrows(PtoException.class, () -> pto.verify("not-a-token"));
        assertThrows(PtoException.class, () -> pto.verify("a.b.c.d"));
        assertThrows(PtoException.class, () -> pto.verify(null));
    }

    @Test
    void shortKeyRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new PtoToken("too-short".getBytes(StandardCharsets.UTF_8), ISSUER));
    }

    @Test
    void audienceAsPlainStringParsed() {
        // 兼容把 aud 写成单个字符串的载荷（部分实现如此）
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", "pacc-client");
        claims.put("sub", "PT1");
        claims.put("exp", Instant.now().plusSeconds(60).getEpochSecond());
        String token = signRaw(KEY, claims);
        assertEquals(List.of("pacc-client"), pto.verify(token).audience());
    }

    @Test
    void jjwtStyleTokenVerifies() {
        // jjwt 输出的典型载荷（aud 为数组），用 PTO 校验必须能通过——保证替换时老令牌不掉线
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("iss", ISSUER);
        claims.put("aud", List.of("pacc-client"));
        claims.put("sub", "PT999");
        claims.put("exp", Instant.now().plusSeconds(60).getEpochSecond());
        String token = signRaw(KEY, claims);
        assertEquals("PT999", pto.verify(token).subject());
    }

    @Test
    void audienceStringStillIssuesAsArray() {
        String token = pto.builder().audience("pacc-client").subject("PT1")
                .expiresAt(Instant.now().plusSeconds(60)).sign();
        // 自己签发的 aud 固定为数组形式
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\"aud\":[\"pacc-client\"]"), payload);
    }

    @Test
    void nullClaimsSkipped() {
        String token = pto.builder().subject("PT1").claim("empty", null)
                .expiresAt(Instant.now().plusSeconds(60)).sign();
        assertFalse(pto.verify(token).has("empty"));
    }

    // ------------------------------ 测试辅助 ------------------------------

    private static String base64Url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** 手工按 HS256 口径签一个令牌，模拟 jjwt 生成的历史令牌。 */
    private static String signRaw(byte[] key, Map<String, Object> claims) {
        try {
            String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
            // 直接复用手写 JSON 编码，避免测试依赖 Jackson
            String payloadJson = buildJson(claims);
            String payload = base64Url(payloadJson);
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            String signature = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
            return header + "." + payload + "." + signature;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String buildJson(Map<String, Object> claims) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (var e : claims.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof List<?> list) {
                sb.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append('"').append(list.get(i)).append('"');
                }
                sb.append(']');
            } else {
                sb.append('"').append(v).append('"');
            }
        }
        return sb.append('}').toString();
    }
}