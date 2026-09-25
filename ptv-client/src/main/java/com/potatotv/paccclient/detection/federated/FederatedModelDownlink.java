package com.potatotv.paccclient.detection.federated;

import com.potatotv.paccclient.Json;
import com.potatotv.paccclient.ai.LocalAiModel;
import com.potatotv.paccclient.ai.PaccModelFormat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DF §4.1.2 聚合模型下发：接收云端 FedAvg 产出的全局参数向量，校验后热更新既有推理层
 * {@link LocalAiModel}；任何校验失败都<b>保留上一版模型</b>（安全回退），不做破坏性替换。
 *
 * <p>采用「接收载荷 → 校验 → 装载」三段式，</p>
 * <ol>
 *   <li><b>接收</b>：可由 {@link #fetchAndAdopt()} 主动拉取配置路径，也可由传输层把下发的
 *       {@code JSON} 解析成 {@code Map} 后调用 {@link #adopt(Map)}；</li>
 *   <li><b>校验</b>：版本非空且未生效、权重全部有限、维度等于
 *       {@link FederatedParameterLayout#parameterCount(int, int)}、摘要与本地复算一致；随后把向量
 *       还原为 {@code AutoencoderModel} 载荷并包成 {@code .paccm} 容器，再由
 *       {@link PaccModelFormat#read(byte[])} 做容器级 SHA-256 复核（纵深防御）；</li>
 *   <li><b>装载</b>：调用 {@link LocalAiModel#load(byte[])}；该方法仅在解析成功后整体替换模型快照，
 *       因此失败时上一版仍然可用——这就是「回退保留」的实现口径。</li>
 * </ol>
 *
 * <p>模型参数与下载权重都是模型增量/参数，不含任何原始事件数据。</p>
 */
public final class FederatedModelDownlink {

    private final FederatedSettings settings;
    private final LocalAiModel aiModel;
    private final String baseUrl;
    private final String token;
    private final HttpClient http;

    private volatile String currentVersion = "";
    private volatile String currentSha256 = "";

    public FederatedModelDownlink(String serverUri, String token, LocalAiModel aiModel, FederatedSettings settings) {
        String base = serverUri == null ? "" : serverUri;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseUrl = base;
        this.token = token == null ? "" : token;
        this.aiModel = aiModel;
        this.settings = settings;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 已生效的联邦模型版本（空表示尚未装载）。 */
    public String currentVersion() {
        return currentVersion;
    }

    /** 已生效的联邦模型权重摘要。 */
    public String currentSha256() {
        return currentSha256;
    }

    /**
     * 校验并装载一份下发的聚合模型载荷。
     *
     * <p>载荷字段：{@code version}、{@code feature_dim}（或 {@code featureDim}，缺省取本地配置）、
     * {@code weights} / {@code weights_json}（逗号分隔浮点串或数值数组）、{@code weights_sha256}（可选，
     * 提供时强制校验）。</p>
     *
     * @return 装载结论；{@code accepted=false} 时 {@code reason} 给出拒绝原因，且既有模型不受影响
     */
    public AdoptionResult adopt(Map<String, Object> payload) {
        try {
            if (payload == null || payload.isEmpty()) {
                return reject("下发载荷为空");
            }
            String version = str(payload.get("version"));
            if (version.isBlank()) {
                return reject("缺少模型版本");
            }
            if (version.equals(currentVersion)) {
                return new AdoptionResult(false, "版本 " + version + " 已生效", version, currentSha256);
            }
            int featureDim = intOf(first(payload, "feature_dim", "featureDim"), settings.featureDim());
            double[] vector = weightsOf(payload);
            if (vector == null) {
                return reject("缺少权重向量（weights / weights_json）");
            }
            if (!GradientCodec.isFinite(vector)) {
                return reject("权重含非有限值（NaN/Inf）");
            }
            int expected = FederatedParameterLayout.parameterCount(featureDim, settings.autoencoderHidden());
            if (vector.length != expected) {
                return reject("权重维度 " + vector.length + " 与布局 " + expected + " 不匹配");
            }
            String actualSha = GradientCodec.sha256Hex(vector);
            String declaredSha = str(first(payload, "weights_sha256", "weightsSha256"));
            if (!declaredSha.isBlank() && !declaredSha.equalsIgnoreCase(actualSha)) {
                return reject("权重摘要不匹配（声明 " + declaredSha + " 实际 " + actualSha + "）");
            }
            byte[] aeBytes = FederatedParameterLayout.toAutoencoderBytes(vector, featureDim, settings.autoencoderHidden());
            byte[] container = PaccModelFormat.encode(new PaccModelFormat.ModelHeader(
                    PaccModelFormat.FORMAT_VERSION, PaccModelFormat.TYPE_AUTOENCODER, featureDim, aeBytes.length), aeBytes);
            // 容器级复核：魔数 + 长度一致性 + 尾部 SHA-256
            PaccModelFormat.read(container);
            // 热替换既有推理层；失败时 LocalAiModel 内部不替换快照，旧模型保持可用
            aiModel.load(container);
            currentVersion = version;
            currentSha256 = actualSha;
            return new AdoptionResult(true, "", version, actualSha);
        } catch (IOException | RuntimeException e) {
            return reject("模型校验/加载失败: " + rootMessage(e));
        }
    }

    /** 主动拉取配置路径的聚合模型并装载；网络/鉴权/解析失败一律按「未接受」处理（保留旧模型）。 */
    public AdoptionResult fetchAndAdopt() {
        if (baseUrl.isBlank()) {
            return reject("未配置服务端地址");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + settings.modelPath()))
                    .header("Authorization", "Bearer " + token)
                    .GET().timeout(Duration.ofSeconds(settings.requestTimeoutSeconds())).build();
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) {
                return reject("模型下发失败 HTTP " + r.statusCode());
            }
            if (r.body() == null || r.body().isBlank()) {
                return reject("模型下发响应为空");
            }
            return adopt(Json.decodeObject(r.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return reject("模型下发请求被中断");
        } catch (IOException | RuntimeException e) {
            return reject("模型下发请求异常: " + rootMessage(e));
        }
    }

    /** 装载结论。 */
    public record AdoptionResult(boolean accepted, String reason, String version, String sha256) {
    }

    // ------------------------------ 解析 ------------------------------

    /** 权重解析：支持逗号分隔浮点串与数值数组两种下发形状。 */
    private static double[] weightsOf(Map<String, Object> payload) {
        Object raw = first(payload, "weights", "weights_json", "weightsJson");
        if (raw == null) {
            return null;
        }
        if (raw instanceof String s) {
            return s.isBlank() ? null : GradientCodec.decode(s);
        }
        if (raw instanceof List<?> list) {
            if (list.isEmpty()) {
                return null;
            }
            double[] out = new double[list.size()];
            for (int i = 0; i < out.length; i++) {
                if (!(list.get(i) instanceof Number n)) {
                    return null;
                }
                out[i] = n.doubleValue();
            }
            return out;
        }
        return null;
    }

    private static Object first(Map<String, Object> payload, String... keys) {
        for (String k : keys) {
            Object v = payload.get(k);
            if (v != null) {
                return v;
            }
        }
        return null;
    }

    private static int intOf(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return def;
            }
        }
        return def;
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString().trim();
    }

    private static AdoptionResult reject(String reason) {
        return new AdoptionResult(false, reason, "", "");
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return cur.getMessage() == null ? cur.toString() : cur.getMessage();
    }
}