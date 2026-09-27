package com.potatotv.paccclient.ops;

import com.potatotv.paccclient.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
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

    /**
     * v5.2 §7.3 上传查端回放录像：正文是加密后的 MJPEG-AVI，解密密钥与元数据走请求头。
     *
     * <p>用二进制正文而不是 JSON+Base64：50MB 的录像走 Base64 会膨胀三分之一，
     * 走 octet-stream 更省带宽也更好排障。</p>
     *
     * @return 是否上传成功
     */
    public boolean uploadReplay(String alertId, byte[] cipher, byte[] key, byte[] iv,
                               int frames, int width, int height, int fps, long durationMs,
                               long plainSize, String sha256) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/player/v52/replay"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/octet-stream")
                .header("X-PACC-Alert-Id", nullToEmpty(alertId))
                .header("X-PACC-Replay-Key", Base64.getEncoder().encodeToString(key))
                .header("X-PACC-Replay-Iv", Base64.getEncoder().encodeToString(iv))
                .header("X-PACC-Replay-Meta",
                        frames + ":" + width + ":" + height + ":" + fps + ":" + durationMs + ":" + plainSize)
                .header("X-PACC-Replay-Sha256", nullToEmpty(sha256))
                .POST(HttpRequest.BodyPublishers.ofByteArray(cipher))
                .timeout(Duration.ofSeconds(60)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                System.err.println("[PTV-Ops] 回放上传被拒绝 HTTP " + r.statusCode());
                return false;
            }
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            System.err.println("[PTV-Ops] 回放上传失败: " + e.getMessage());
            return false;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * v5.4 APM：批量上报秒级采样（body 已由 {@code ApmCollector} 编码为契约 JSON）。
     * 返回是否上报成功——调用方据此决定丢弃还是保留本地缓冲。
     */
    public boolean reportApmBatch(String jsonBody) {
        return post("/api/player/apm/batch", jsonBody);
    }

    /** v5.4 安全：上报反调试/反注入/完整性事件批（body 已由 {@code SecurityReporter} 编码）。 */
    public boolean reportSecurityEvents(String jsonBody) {
        return post("/api/player/security/events", jsonBody);
    }

    /**
     * DF §4.1.2 端侧联邦：上报一份本地梯度（模型增量）。
     *
     * <p>body 由 {@code GradientUploader} 编码，只含客户端标识、梯度向量与样本数，不含任何原始采集
     * 数据。路径由 {@code FederatedSettings#uploadPath()} 决定，故不在本类硬编码。</p>
     *
     * <p>服务端校验失败会返回 200 + {@code accepted=false}，这里一并按失败处理，交给上传器退避重试。</p>
     */
    public boolean submitFederatedUpdate(String path, String jsonBody) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(8)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2 || r.body() == null) {
                System.err.println("[PTV-Ops] 梯度上报被拒绝 HTTP " + r.statusCode());
                return false;
            }
            Object accepted = Json.decodeObject(r.body()).get("accepted");
            return accepted == null || Boolean.parseBoolean(String.valueOf(accepted));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            System.err.println("[PTV-Ops] 梯度上报失败: " + e.getMessage());
            return false;
        }
    }

    /**
     * v5.4 远程证明：领取一次挑战，返回 challenge_id 与 nonce；失败返回 null。
     */
    public AttestationChallenge requestAttestationChallenge(String clientVersion, String platform,
                                                            String codeHash, String configHash) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_version", clientVersion);
        body.put("platform", platform);
        body.put("code_hash", codeHash);
        body.put("config_hash", configHash);
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/player/attestation/challenge"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.encode(body), StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(6)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2 || r.body() == null) return null;
            Map<String, Object> parsed = Json.decodeObject(r.body());
            String challengeId = stringValue(parsed.get("challenge_id"));
            String nonce = stringValue(parsed.get("nonce"));
            if (challengeId.isEmpty()) return null;
            return new AttestationChallenge(challengeId, nonce);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("[PTV-Ops] 领取证明挑战失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * v5.4 远程证明：提交应答，返回服务端原始响应体；HTTP 非 2xx 或异常返回 null。
     */
    public String respondAttestation(String jsonBody) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/player/attestation/respond"))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .timeout(Duration.ofSeconds(6)).build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                System.err.println("[PTV-Ops] 证明应答被拒绝 HTTP " + r.statusCode());
                return null;
            }
            return r.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            System.err.println("[PTV-Ops] 证明应答提交失败: " + e.getMessage());
            return null;
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** 远程证明挑战（challenge_id + nonce）。 */
    public record AttestationChallenge(String challengeId, String nonce) {
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