package com.potatotv.paccclient.ops;

import com.potatotv.paccclient.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * v4.7 客户端运维客户端：向 PTV 上报崩溃 / 性能数据并拉取远程配置。
 * <p>走玩家态接口（/api/player/ops/**，持玩家 JWT 的 Authorization Bearer），
 * 与管理端 /api/admin/ops/** 共用落库与聚合，仅鉴权维度不同。</p>
 * <p>网络层仅用 JDK {@link HttpClient}，body 由 {@link Json} 编码。</p>
 */
public final class OpsClient {

    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    public OpsClient(String serverUri, String token) {
        String base = serverUri;
        if (base != null && base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.baseUrl = base;
        this.token = token;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 上报性能采样。cpuPercent 0-100，memMb 为进程占用 MB。返回是否上报成功。 */
    public boolean reportTelemetry(String clientVersion, String os, double cpuPercent, long memMb,
                                   Long fpsImpactPercent, Long detectionLatencyMs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_version", clientVersion);
        body.put("os", os);
        body.put("cpu_percent", cpuPercent);
        body.put("mem_mb", memMb);
        body.put("fps_impact_percent", fpsImpactPercent);
        body.put("detection_latency_ms", detectionLatencyMs);
        return post("/api/player/ops/report/telemetry", Json.encode(body));
    }

    /** 上报崩溃。返回是否上报成功。 */
    public boolean reportCrash(String clientVersion, String os, String arch, String platform,
                               String stackTrace, String contextJson, String controller) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_version", clientVersion);
        body.put("os", os);
        body.put("arch", arch);
        body.put("platform", platform);
        body.put("stack_trace", stackTrace);
        body.put("context_json", contextJson);
        body.put("controller", controller);
        return post("/api/player/ops/report/crash", Json.encode(body));
    }

    /**
     * v5.2 §7.1 上报硬件指纹摘要（只发哈希，原始序列号不出本机）。返回是否上报成功。
     *
     * @param fingerprintHash 全维度加权指纹（SHA-256 十六进制）
     */
    public boolean reportHardwareFingerprint(String fingerprintHash) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("fingerprint_hash", fingerprintHash);
        return post("/api/player/v52/device/fingerprint", Json.encode(body));
    }

    /** 拉取生效远程配置（{config:{key:value}}）。失败返回空 Map。 */
    public Map<String, Object> fetchRemoteConfig() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/player/ops/config/active"))
                .header("Authorization", "Bearer " + token)
                .GET().timeout(Duration.ofSeconds(6)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2 || r.body() == null) return Map.of();
            Object cfgObj = Json.decodeObject(r.body()).get("config");
            if (cfgObj instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cfg = (Map<String, Object>) m;
                return cfg;
            }
            return Map.of();
        } catch (Exception e) {
            System.err.println("[PTV-Ops] 拉取远程配置失败: " + e.getMessage());
            return Map.of();
        }
    }

    private boolean post(String path, String json) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(6)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            return r.statusCode() / 100 == 2;
        } catch (Exception e) {
            System.err.println("[PTV-Ops] 上报失败 " + path + ": " + e.getMessage());
            return false;
        }
    }
}