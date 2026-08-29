package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.Account;
import com.potatotv.pacc.domain.DetectionEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AI 推理服务客户端（Python / FastAPI）。
 * <p>按配置调用 {@code POST /api/inference/score} 获取 AI 模型分；
 * 服务不可用或未启用时返回空，调用方回退本地评分，保证主链路不依赖 AI。</p>
 */
@Service
public class AiInferenceClient {

    private final boolean enabled;
    private final RestClient client;

    public AiInferenceClient(@Value("${pacc.ai.enabled:false}") boolean enabled,
                             @Value("${pacc.ai.inference-url:http://ai:8000}") String baseUrl) {
        this.enabled = enabled;
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(1500);
        f.setReadTimeout(1500);
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(f).build();
    }

    /** 调用 AI 推理服务；返回空表示未启用或调用失败。 */
    public Optional<Double> score(DetectionEvent event, Account account) {
        if (!enabled) return Optional.empty();
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("event_type", event.getEventType());
            body.put("severity", event.getSeverity());
            body.put("client_risk", event.getClientRiskScore());
            body.put("history_factor", account != null ? 1.0 - account.getReputation() / 100.0 : 0.0);

            Map<?, ?> resp = client.post()
                    .uri("/api/inference/score")
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            if (resp == null || resp.get("risk_score") == null) return Optional.empty();
            return Optional.of(Double.parseDouble(String.valueOf(resp.get("risk_score"))));
        } catch (Exception e) {
            // AI 服务不可用不阻塞主链路
            return Optional.empty();
        }
    }

    /** v4.1 行为分类：128 维特征 -> 作弊概率。 */
    public Optional<Map<String, Object>> classifyBehavior(Map<String, Double> features) {
        return call("/api/inference/behavior", Map.of("features", features));
    }

    /** v4.1 时序异常检测。 */
    public Optional<Map<String, Object>> temporalAnomaly(Object sequence) {
        return call("/api/inference/temporal", Map.of("sequence", sequence));
    }

    /** v4.1 人类行为模拟度评估。 */
    public Optional<Map<String, Object>> humanize(Map<String, Double> trajectory) {
        return call("/api/inference/humanize", Map.of("trajectory", trajectory));
    }

    /** v4.1 跨账号行为关联。 */
    public Optional<Map<String, Object>> correlate(String pteid, Map<String, Double> features) {
        return call("/api/inference/correlate", Map.of("pteid", pteid, "features", features));
    }

    /** v4.1 自适应对抗学习。 */
    public Optional<Map<String, Object>> adapt(Object samples) {
        return call("/api/inference/adapt", Map.of("samples", samples));
    }

    private Optional<Map<String, Object>> call(String uri, Map<String, Object> body) {
        if (!enabled) return Optional.empty();
        try {
            Map<?, ?> resp = client.post().uri(uri).body(body).retrieve().body(Map.class);
            return Optional.ofNullable(resp).map(m -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) m;
                return cast;
            });
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
