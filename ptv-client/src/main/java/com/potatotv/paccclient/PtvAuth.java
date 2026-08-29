package com.potatotv.paccclient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * PTV 服务器登录客户端：调用 /api/auth/login 换取真实 JWT 访问令牌，
 * 供 WSS 握手认证使用（演示模式自动登录，生产可替换为安全存储中的令牌）。
 * <p>仅使用 JDK 内置 java.net.http，保持零第三方运行时依赖。</p>
 */
public final class PtvAuth {

    /** 登录会话：PTEID + JWT 访问令牌。 */
    public record Session(String pteid, String accessToken, long expiresAt) {}

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * 调用 PTV /api/auth/login 获取访问令牌。
     *
     * @param serverUri PTV 服务 HTTP 基地址，如 http://localhost:8080
     * @throws IllegalStateException 登录失败或服务器不可达
     */
    public Session login(String serverUri, String identity, String password, boolean remember) {
        String base = serverUri == null ? "" : serverUri.replaceAll("/+$", "");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("identity", identity);
        body.put("password", password);
        body.put("remember", remember);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(base + "/api/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "PACC-PlayerClient/4.0.0")
                .POST(HttpRequest.BodyPublishers.ofString(Json.encode(body), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            String json = resp.body() == null ? "" : resp.body();
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("登录失败(" + resp.statusCode() + "): " + field(json, "error", "未知错误"));
            }
            String pteid = field(json, "pteid", null);
            String token = field(json, "access_token", null);
            if (token == null || token.isEmpty()) {
                throw new IllegalStateException("登录响应缺少 access_token");
            }
            long expiresAt = -1L;
            try {
                expiresAt = Long.parseLong(field(json, "expires_at", "-1"));
            } catch (NumberFormatException ignored) {
            }
            return new Session(pteid, token, expiresAt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("登录请求被中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("无法连接 PTV 服务器(" + base + "): " + e.getMessage(), e);
        }
    }

    // ---- 极简 JSON 字段提取（字符串 / 数字 / 布尔） ----
    private static String field(String json, String key, String def) {
        String marker = "\"" + key + "\":";
        int i = json.indexOf(marker);
        if (i < 0) return def;
        int j = i + marker.length();
        while (j < json.length() && Character.isWhitespace(json.charAt(j))) j++;
        if (j >= json.length()) return def;
        char c = json.charAt(j);
        if (c == '"') {
            int end = json.indexOf('"', j + 1);
            return end < 0 ? def : json.substring(j + 1, end);
        }
        int end = j;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) end++;
        return end > j ? json.substring(j, end) : def;
    }
}
