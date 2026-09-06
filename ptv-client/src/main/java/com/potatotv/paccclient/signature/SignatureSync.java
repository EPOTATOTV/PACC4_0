package com.potatotv.paccclient.signature;

import com.potatotv.paccclient.Json;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 端侧特征库热更新器：拉取服务端下发的特征包（JSON），校验内容摘要与 HMAC 签名后
 * 在内存中热替换检测规则，客户端无需重启即可加载新特征。
 * <p>校验口径与服务端 {@code SignatureLibraryService/SigSigner} 保持一致：
 * 摘要 = SHA-256(排序后各规则 {@code id:pattern:risk:version} 以换行拼接) 的 hex；
 * 签名 = HMAC-SHA256(secret, "{digest}|{version}")。</p>
 * <p>任一步不对称即视为被篡改，整体拒绝，保留上一份已生效规则。</p>
 */
public final class SignatureSync {

    private final AtomicReference<List<SignatureRule>> rulesRef;
    private final AtomicReference<String> versionRef = new AtomicReference<>("");
    private final AtomicReference<String> digestRef = new AtomicReference<>("");
    private final String secret;
    private final boolean verifySignature;

    public SignatureSync(String secret) {
        this.secret = secret == null ? "" : secret;
        this.verifySignature = !this.secret.isBlank();
        this.rulesRef = new AtomicReference<>(Collections.emptyList());
    }

    public List<SignatureRule> rules() {
        return rulesRef.get();
    }

    public int ruleCount() {
        return rulesRef.get().size();
    }

    public String version() {
        return versionRef.get();
    }

    public String digest() {
        return digestRef.get();
    }

    /**
     * 应用一个来自服务端的特征差异包（契约见 {@code SignatureLibraryService#diff}）：
     * <pre>{edition, after_version, count, library_version, changes:[{id,name,pattern,riskLevel,version}], digest, signature}</pre>
     * digest = SHA-256(排序后各规则 {@code id:pattern:riskLevel:version} 以换行拼接) 的 hex；
     * signature = HMAC-SHA256(secret, "{digest}|{library_version}")。
     * 校验失败抛出 {@link IllegalStateException} 且不改变当前已生效规则。
     */
    public void apply(String json) {
        Map<String, Object> root = Json.decodeObject(json);
        String libraryVersion = String.valueOf(root.getOrDefault("library_version", ""));
        String expectedDigest = String.valueOf(root.getOrDefault("digest", ""));
        Map<String, SignatureRule> ruleMap = asRules(root.get("changes"));

        List<String> canon = new ArrayList<>(ruleMap.size());
        for (SignatureRule r : ruleMap.values()) canon.add(r.canonical());
        Collections.sort(canon);
        String actual = sha256Hex(String.join("\n", canon));
        if (expectedDigest.isBlank() || !constantTimeEquals(actual, expectedDigest)) {
            throw new IllegalStateException("特征包内容摘要不匹配");
        }

        if (verifySignature) {
            String provided = String.valueOf(root.getOrDefault("signature", ""));
            String expected = hmacHex(secret, expectedDigest + "|" + libraryVersion);
            if (provided.isBlank() || !constantTimeEquals(expected, provided)) {
                throw new IllegalStateException("特征包签名校验失败");
            }
        }

        List<SignatureRule> next = new ArrayList<>(ruleMap.values());
        next.sort((a, b) -> a.id.compareTo(b.id));
        rulesRef.set(Collections.unmodifiableList(next));
        versionRef.set(libraryVersion);
        digestRef.set(expectedDigest);
    }

    /** 把 rules 顶层数组解析为 id → 规则映射；字段缺失视为非法。 */
    private static Map<String, SignatureRule> asRules(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalStateException("特征包缺少 rules 数组");
        }
        var map = new java.util.LinkedHashMap<String, SignatureRule>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> m)) throw new IllegalStateException("规则项非法");
            String id = str(m.get("id"));
            String name = str(m.get("name"));
            String pattern = str(m.get("pattern"));
            if (id.isEmpty() || name.isEmpty() || pattern.isEmpty()) {
                throw new IllegalStateException("规则缺少 id/name/pattern");
            }
            long version = num(m.get("version"), 1L);
            int risk = (int) num(m.get("riskLevel"), 1L);
            map.put(id, new SignatureRule(id, name, pattern, Math.max(1, Math.min(5, risk)), version));
        }
        return map;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static long num(Object o, long def) {
        if (o instanceof Number n) return n.longValue();
        if (o != null) {
            try {
                return Long.parseLong(o.toString());
            } catch (NumberFormatException ignored) {
                // 解析失败回退默认
            }
        }
        return def;
    }

    private static String sha256Hex(String data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }

    private static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    /** 常量时间比较，防时序攻击。 */
    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}