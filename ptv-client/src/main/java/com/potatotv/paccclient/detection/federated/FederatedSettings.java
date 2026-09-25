package com.potatotv.paccclient.detection.federated;

import com.potatotv.paccclient.detection.FeatureSchema;

import java.util.Map;
import java.util.function.Consumer;

/**
 * DF §4.1.2 端侧联邦配置：端点、隐私上限、队列与重试参数。
 *
 * <p><b>端点契约与已知不匹配（须由服务端补玩家路由）</b>：云端目前把联邦端点挂在
 * {@code /api/admin/df/federated/**}（{@code AdminKeyFilter} + {@code @RequirePermission} 保护），
 * 端侧无法以管理员身份鉴权，因此<b>不能硬编码调用该路径</b>。本配置的默认值指向「玩家侧路由」
 * {@value #DEFAULT_UPLOAD_PATH} / {@value #DEFAULT_MODEL_PATH}——该路由需由服务端后续新增
 * （与既有 {@code /api/player/**} 玩家 JWT 保护一致），再用环境变量/系统属性覆盖即可，端侧无需改码。</p>
 *
 * <p>取值顺序：环境变量 &gt; 系统属性 &gt; 内置默认值。</p>
 */
public final class FederatedSettings {

    /** 默认梯度上报路径（玩家侧路由，待服务端提供）。 */
    public static final String DEFAULT_UPLOAD_PATH = "/api/player/df/federated/updates";
    /** 默认聚合模型下发路径（玩家侧路由，待服务端提供）。 */
    public static final String DEFAULT_MODEL_PATH = "/api/player/df/federated/model/latest";

    private final String uploadPath;
    private final String modelPath;
    private final double maxGradientNorm;
    private final int minSamples;
    private final int autoencoderHidden;
    private final int maxQueuedGradients;
    private final long retryDelayMillis;
    private final int maxAttempts;
    private final int requestTimeoutSeconds;

    private FederatedSettings(String uploadPath, String modelPath, double maxGradientNorm, int minSamples,
                              int autoencoderHidden, int maxQueuedGradients, long retryDelayMillis,
                              int maxAttempts, int requestTimeoutSeconds) {
        this.uploadPath = uploadPath;
        this.modelPath = modelPath;
        this.maxGradientNorm = maxGradientNorm;
        this.minSamples = minSamples;
        this.autoencoderHidden = autoencoderHidden;
        this.maxQueuedGradients = maxQueuedGradients;
        this.retryDelayMillis = retryDelayMillis;
        this.maxAttempts = maxAttempts;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }

    /** 内置默认配置（无环境变量/系统属性覆盖）。 */
    public static FederatedSettings defaults() {
        return new FederatedSettings(DEFAULT_UPLOAD_PATH, DEFAULT_MODEL_PATH, 50.0, 20, 8, 16, 5000L, 3, 8);
    }

    /** 从环境变量/系统属性读取配置（缺失项回退默认值）。 */
    public static FederatedSettings fromEnvironment() {
        FederatedSettings d = defaults();
        return new FederatedSettings(
                get("pacc.df.federated.upload-path", "PACC_DF_FEDERATED_UPLOAD_PATH", d.uploadPath),
                get("pacc.df.federated.model-path", "PACC_DF_FEDERATED_MODEL_PATH", d.modelPath),
                getDouble("pacc.df.federated.max-gradient-norm", "PACC_DF_FEDERATED_MAX_GRADIENT_NORM", d.maxGradientNorm),
                getInt("pacc.df.federated.min-samples", "PACC_DF_FEDERATED_MIN_SAMPLES", d.minSamples),
                getInt("pacc.df.federated.autoencoder-hidden", "PACC_DF_FEDERATED_AUTOENCODER_HIDDEN", d.autoencoderHidden),
                getInt("pacc.df.federated.max-queued", "PACC_DF_FEDERATED_MAX_QUEUED", d.maxQueuedGradients),
                getInt("pacc.df.federated.retry-delay-ms", "PACC_DF_FEDERATED_RETRY_DELAY_MS", (int) d.retryDelayMillis),
                getInt("pacc.df.federated.max-attempts", "PACC_DF_FEDERATED_MAX_ATTEMPTS", d.maxAttempts),
                getInt("pacc.df.federated.request-timeout-seconds", "PACC_DF_FEDERATED_REQUEST_TIMEOUT_SECONDS",
                        d.requestTimeoutSeconds));
    }

    /** 梯度上报路径。 */
    public String uploadPath() {
        return uploadPath;
    }

    /** 聚合模型下发路径。 */
    public String modelPath() {
        return modelPath;
    }

    /** 梯度 L2 范数上限（上传前裁剪线，与云端离群拒绝线对应）。 */
    public double maxGradientNorm() {
        return maxGradientNorm;
    }

    /** 允许上传所需的最小本地样本数。 */
    public int minSamples() {
        return minSamples;
    }

    /** 端侧自编码器隐藏层维度（联邦向量长度的决定因素）。 */
    public int autoencoderHidden() {
        return autoencoderHidden;
    }

    /** 特征维度（与 {@link FeatureSchema} 对齐）。 */
    public int featureDim() {
        return FeatureSchema.size();
    }

    /** 联邦向量维度（= 自编码器参数总数）。 */
    public int gradientDim() {
        return FederatedParameterLayout.parameterCount(featureDim(), autoencoderHidden());
    }

    /** 待上传梯度队列上限（满则丢弃最旧，绝不无界增长）。 */
    public int maxQueuedGradients() {
        return maxQueuedGradients;
    }

    /** 上传失败后的重试间隔（毫秒）。 */
    public long retryDelayMillis() {
        return retryDelayMillis;
    }

    /** 单条梯度的最大尝试次数（含首次）。 */
    public int maxAttempts() {
        return maxAttempts;
    }

    /** 模型下发请求超时（秒）。 */
    public int requestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    /** 按配置构建隐私护栏（范数上限 + 样本数门限）。 */
    public GradientPrivacyGuard privacyGuard() {
        return new GradientPrivacyGuard(maxGradientNorm, minSamples);
    }

    /**
     * 按配置构建梯度上传器（队列上限 / 重试间隔 / 重试次数）。
     *
     * @param clientId  客户端标识（最小化：仅一个匿名设备/玩家 id）
     * @param transport 传输落点（生产为 {@code WssReporter#sendPayload}）
     */
    public GradientUploader uploader(String clientId, Consumer<Map<String, Object>> transport) {
        return new GradientUploader(clientId, privacyGuard(), transport, maxQueuedGradients,
                retryDelayMillis, maxAttempts);
    }

    private static String get(String key, String env, String def) {
        String v = System.getenv(env);
        if (v == null || v.isBlank()) {
            v = System.getProperty(key);
        }
        return (v == null || v.isBlank()) ? def : v.trim();
    }

    private static int getInt(String key, String env, int def) {
        try {
            return Integer.parseInt(get(key, env, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static double getDouble(String key, String env, double def) {
        try {
            return Double.parseDouble(get(key, env, String.valueOf(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}