package com.potatotv.pcu;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 检查更新（设计文档 §4.4 第 1 步）：{@code GET /v1/update/check}。
 */
public final class UpdateChecker {

    /** 检查更新路径。 */
    public static final String CHECK_PATH = "/v1/update/check";

    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());

    private final PcuConfig config;
    private final HttpClient http;

    public UpdateChecker(PcuConfig config) {
        this(config, HttpClients.create(config));
    }

    public UpdateChecker(PcuConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /** 请求「检查更新」。 */
    public UpdateManifest check() {
        URI uri = buildUri();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(config.requestTimeout())
                .header("User-Agent", config.userAgent())
                .header("Accept", "application/json")
                .GET()
                .build();
        String body = Retry.call("检查更新", config.maxRetries(), config.retryBaseDelay(), () -> {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status != 200) {
                // 异常信息只带路径与状态码：URL 里含 pteid，进日志与异常都属于隐私泄露
                throw new IOException("更新接口返回 HTTP " + status);
            }
            return response.body();
        });
        UpdateManifest manifest = UpdateManifest.fromJson(PcuJson.parse(body));
        LOG.info(() -> "检查更新完成：hasUpdate=" + manifest.hasUpdate()
                + " latest=" + manifest.latestVersion());
        return manifest;
    }

    /** 拼装检查更新的 URL（供测试与联调核对参数）。 */
    public URI buildUri() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("platform", config.platform().wire());
        params.put("current_version", config.currentVersion());
        params.put("channel", config.channel().wire());
        if (config.pteid() != null && !config.pteid().isBlank()) {
            params.put("pteid", config.pteid());
        }
        StringBuilder sb = new StringBuilder(config.baseUrl()).append(CHECK_PATH).append('?');
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return URI.create(sb.toString());
    }
}