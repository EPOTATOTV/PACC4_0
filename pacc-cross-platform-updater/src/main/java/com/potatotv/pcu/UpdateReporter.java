package com.potatotv.pcu;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * 更新结果上报（设计文档 §4.11 {@code POST /v1/update/report}）：服务端据此统计成功率/失败率，
 * 并在回滚时把对应版本标记为有问题、暂停灰度。
 */
public final class UpdateReporter {

    /** 上报路径。 */
    public static final String REPORT_PATH = "/v1/update/report";

    /** 错误信息长度上限：够定位问题，又不会把堆栈整段塞进上报体。 */
    private static final int MAX_ERROR_LENGTH = 500;

    private static final Logger LOG = Logger.getLogger(UpdateReporter.class.getName());

    private final PcuConfig config;
    private final HttpClient http;

    public UpdateReporter(PcuConfig config) {
        this(config, HttpClients.create(config));
    }

    public UpdateReporter(PcuConfig config, HttpClient http) {
        this.config = config;
        this.http = http;
    }

    /**
     * 上报一次更新结果；失败会抛异常。
     *
     * <p>更新主流程请用 {@link #reportQuietly}——上报本身失败不该让已经成功的更新变成失败。</p>
     */
    public void report(UpdateStatus status, String fromVersion, String toVersion, String errorMessage) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pteid", config.pteid());
        body.put("platform", config.platform().wire());
        body.put("from_version", fromVersion);
        body.put("to_version", toVersion);
        body.put("status", status.wire());
        if (errorMessage != null && !errorMessage.isBlank()) {
            body.put("error_message", truncate(errorMessage));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + REPORT_PATH))
                .timeout(config.requestTimeout())
                .header("User-Agent", config.userAgent())
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(PcuJson.write(body), StandardCharsets.UTF_8))
                .build();
        Retry.call("上报更新结果", config.maxRetries(), config.retryBaseDelay(), () -> {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new IOException("上报接口返回 HTTP " + response.statusCode());
            }
            return null;
        });
    }

    /** 上报但不影响主流程：仅记录日志。 */
    public void reportQuietly(UpdateStatus status, String fromVersion, String toVersion, String errorMessage) {
        try {
            report(status, fromVersion, toVersion, errorMessage);
        } catch (RuntimeException e) {
            LOG.warning(() -> "更新结果上报失败（不影响本地更新结果）：" + e.getMessage());
        }
    }

    private static String truncate(String text) {
        String flat = text.replaceAll("\\s+", " ").trim();
        return flat.length() <= MAX_ERROR_LENGTH ? flat : flat.substring(0, MAX_ERROR_LENGTH) + "…";
    }
}