package com.potatotv.pacc.util;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 开放 API 请求签名（v4.8）：
 * <pre>signature = HMAC-SHA256(secret, canonical)
 * canonical = "{METHOD}\n{PATH}\n{TIMESTAMP_MS}\n{BODY_SHA256_HEX}"</pre>
 * 客户端出示 X-PTV-Key / X-PTV-Timestamp / X-PTV-Signature / X-PTV-Nonce 请求头，
 * 服务端以库中解密出的密钥复算比对，并校验时间窗口与 nonce 防重放。
 */
public final class ApiSignature {

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private ApiSignature() {
    }

    public static String canonical(String method, String path, String timestamp, String bodySha256) {
        return method + "\n" + path + "\n" + timestamp + "\n" + (bodySha256 == null ? "" : bodySha256);
    }

    public static String bodySha256Hex(String body) {
        return sha256Hex(body == null ? "" : body);
    }

    public static String hmacHex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 计算失败", e);
        }
    }

    public static String sha256Hex(String data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 失败", e);
        }
    }

    /** 常量时间比较，防时序攻击。 */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(HEX[(v >> 4) & 0xF]).append(HEX[v & 0xF]);
        }
        return sb.toString();
    }
}