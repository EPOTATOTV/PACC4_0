package com.potatotv.paccclient.signature;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SignatureSyncTest {

    private static final String SECRET = "pacc-sig-test-secret";

    @Test
    void appliesValidSignedBundle() {
        String json = bundle("v4.3.1", SECRET);
        SignatureSync sync = new SignatureSync(SECRET);
        sync.apply(json);
        assertEquals("v4.3.1", sync.version());
        assertEquals(2, sync.ruleCount());
        assertEquals("sig2", sync.rules().get(1).id);
    }

    @Test
    void rejectsTamperedContentDigest() {
        String json = bundle("v4.3.1", SECRET);
        // 篡改规则内容，digest 不再匹配
        String tampered = json.replace("killaura-pat", "KILLAURA-PAT");
        SignatureSync sync = new SignatureSync(SECRET);
        assertThrows(IllegalStateException.class, () -> sync.apply(tampered));
        assertEquals(0, sync.ruleCount(), "校验失败必须保留原规则");
    }

    @Test
    void rejectsBadSignature() {
        String json = bundle("v4.3.1", "wrong-secret");
        SignatureSync sync = new SignatureSync(SECRET);
        assertThrows(IllegalStateException.class, () -> sync.apply(json));
        assertEquals(0, sync.ruleCount());
    }

    @Test
    void rejectsWhenSignatureMissing() {
        String json = bundle("v4.3.1", SECRET);
        String noSig = json.substring(0, json.lastIndexOf(',') > 0 ? json.lastIndexOf(',') : 0) + "}";
        SignatureSync sync = new SignatureSync(SECRET);
        assertThrows(IllegalStateException.class, () -> sync.apply(noSig));
    }

    @Test
    void appliesWithoutSignatureWhenKeyBlank() {
        String json = bundle("v4.3.1", SECRET);
        SignatureSync sync = new SignatureSync("");
        sync.apply(json);
        assertEquals(2, sync.ruleCount());
    }

    // ---- 与服务端一致地构造合法包 ----
    private static String bundle(String libraryVersion, String secret) {
        List<String> canon = new ArrayList<>(List.of(
                "sig1:killa-aura:3:1",
                "sig2:ghost-client-1.2.3:5:2"));
        Collections.sort(canon);
        String digest = sha256Hex(String.join("\n", canon));
        String signature = hmacHex(secret, digest + "|" + libraryVersion);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("edition", "JAVA");
        root.put("after_version", 0);
        root.put("count", 2);
        root.put("library_version", libraryVersion);

        List<Object> changes = new ArrayList<>();
        changes.add(mapOf("b", sig( "sig2", "ghost detector", "ghost-client-1.2.3", 5, 2)));
        changes.add(mapOf( "a", sig("sig1", "killaura v3", "killa-aura", 3, 1)));
        root.put("changes", changes);
        root.put("digest", digest);
        root.put("signature", signature);

        StringBuilder sb = new StringBuilder("{");
        root.forEach((k, v) -> {
            if (sb.length() > 1) sb.append(',');
            sb.append('"').append(k).append("\":");
            if (v instanceof String s) sb.append('"').append(s).append('"');
            else if (v instanceof List<?> l) {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(',');
                    appendObj(sb, (Map<?, ?>) l.get(i));
                }
                sb.append(']');
            } else sb.append(v);
        });
        return sb.append('}').toString();
    }

    // 辅助：用键序保证 changes 内字段顺序稳定
    private static Map<String, Object> mapOf(String pad, Object v) {
        // 忽略 pad，仅用于占位保持签名稳定
        return (Map<String, Object>) v;
    }

    private static Object[] sigFieldOrder = new Object[0];

    private static Map<String, Object> sig(String id, String name, String pattern, int risk, long version) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("pattern", pattern);
        m.put("riskLevel", risk);
        m.put("version", version);
        return m;
    }

    private static void appendObj(StringBuilder sb, Map<?, ?> m) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            Object v = e.getValue();
            sb.append('"').append(e.getKey()).append("\":");
            sb.append(v instanceof String s ? '"' + s + '"' : v);
        }
        sb.append('}');
    }

    private static String sha256Hex(String data) {
        try {
            byte[] b = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] b = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(b.length * 2);
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}