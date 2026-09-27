package com.potatotv.pcu;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测试用的假更新服务：只实现 {@code /v1/update/check}、{@code /v1/update/report}
 * 与制品下载三条路径，够跑通端侧整条链路。
 */
final class FakeUpdateServer implements AutoCloseable {

    private final HttpServer server;

    /** 待下发的制品字节。 */
    byte[] artifact = "新版本".getBytes(StandardCharsets.UTF_8);
    String latestVersion = "5.5.0";
    boolean hasUpdate = true;
    boolean forceUpdate;
    String minAppVersion;
    /** 覆盖清单里的 checksum，用来构造「下到的包校验不过」。 */
    String checksumOverride;
    /** 覆盖清单里的 size，用来构造「长度不符」。 */
    Long sizeOverride;

    final List<Map<String, Object>> reports = new ArrayList<>();
    int checkCount;

    FakeUpdateServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/update/check", this::handleCheck);
        server.createContext("/v1/update/report", this::handleReport);
        server.createContext("/files/artifact.bin", this::handleArtifact);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handleCheck(HttpExchange exchange) throws IOException {
        checkCount++;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("has_update", hasUpdate);
        body.put("platform", "windows");
        if (hasUpdate) {
            body.put("latest_version", latestVersion);
            body.put("download_url", baseUrl() + "/files/artifact.bin");
            body.put("checksum", checksumOverride != null ? checksumOverride : Sha256.hex(artifact));
            body.put("size", sizeOverride != null ? sizeOverride : (long) artifact.length);
            body.put("force_update", forceUpdate);
            body.put("changelog", "修了几个问题");
            if (minAppVersion != null) {
                body.put("min_app_version", minAppVersion);
            }
        }
        respond(exchange, 200, PcuJson.write(body).getBytes(StandardCharsets.UTF_8));
    }

    private void handleReport(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Object parsed = PcuJson.parse(text);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    copy.put(String.valueOf(e.getKey()), e.getValue());
                }
                reports.add(copy);
            }
        }
        respond(exchange, 200, "{}".getBytes(StandardCharsets.UTF_8));
    }

    private void handleArtifact(HttpExchange exchange) throws IOException {
        respond(exchange, 200, artifact);
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}