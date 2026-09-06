package com.potatotv.sdk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * PACC 开放 API（/api/v1/**）开发者客户端 SDK 示例（Java 17+）。
 *
 * <p>鉴权头（每个请求均需携带）：
 * <pre>X-PTV-Key       = keyId
 * X-PTV-Timestamp = 当前毫秒时间戳（与服务器差值需 &lt; 300s）
 * X-PTV-Nonce     = 一次性随机串（防重放）
 * X-PTV-Signature = HMAC-SHA256(secret, canonical)
 * canonical = METHOD\nPATH\nTIMESTAMP\nBODY_SHA256_HEX</pre>
 *
 * <p>完整文档见开发的 OpenAPI 3.0 / Swagger UI 页面。</p>
 */
public final class PaccClientExample {

    private final String baseUrl;
    private final String keyId;
    private final String secret;
    private final HttpClient http = HttpClient.newHttpClient();

    public PaccClientExample(String baseUrl, String keyId, String secret) {
        this.baseUrl = baseUrl;
        this.keyId = keyId;
        this.secret = secret;
    }

    /** GET 请求示例：拉取玩家信誉。 */
    public String getReputation(String pteid) throws Exception {
        String path = "/api/v1/players/" + pteid + "/reputation";
        return send("GET", path, "");
    }

    private String send(String method, String path, String body) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String bodySha = sha256Hex(body);
        String canonical = method + "\n" + path + "\n" + timestamp + "\n" + bodySha;
        String signature = hmacHex(secret, canonical);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("X-PTV-Key", keyId)
                .header("X-PTV-Timestamp", timestamp)
                .header("X-PTV-Nonce", nonce)
                .header("X-PTV-Signature", signature);
        if (body.isEmpty()) {
            builder.method("GET", HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json")
                    .method("POST", HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    private static String sha256Hex(String data) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
        return hex(d);
    }

    private static String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) sb.append(String.format("%02x", v));
        return sb.toString();
    }

    public static void main(String[] args) throws Exception {
        // 使用开发者门户申请到的 keyId / secret 后填入
        var client = new PaccClientExample("https://api.potatotv.asia", "kpt_xxx", "your-secret");
        System.out.println(client.getReputation("PTxxxxxxxxxxxx"));
    }
}