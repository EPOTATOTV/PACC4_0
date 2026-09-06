package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.config.PinnedTrustManagerFactory;
import com.potatotv.pacc.domain.ApiKey;
import com.potatotv.pacc.util.ApiSignature;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * v4.8 Webhook 推送（针对开放 API 租户）：向密钥配置的 webhookUrl 推送事件，
 * 用该密钥的 webhookSecret 对 body 做 HMAC-SHA256 签名（头 X-PTV-Signature），
 * 接收方校验签名可防篡改。沿用 {@link WebhookDispatcher} 的虚拟线程 + 可选 TLS 固定。
 */
@Slf4j
@Service
@SuppressWarnings("null")
public class OpenApiWebhookService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    @Value("${pacc.webhook.retry:3}")
    private int maxRetry;

    public OpenApiWebhookService(PinnedTrustManagerFactory tlsPin) {
        RestTemplate rt = new RestTemplate();
        if (tlsPin.active()) {
            org.springframework.http.client.ClientHttpRequestFactory f = tlsPin.requestFactory();
            if (f != null) rt.setRequestFactory(f);
        }
        this.restTemplate = rt;
    }

    private RestTemplate restTemplate;

    /** 推送一个事件（尽力而为、异步、带重试）。未配置 webhook 则跳过。 */
    public void push(ApiKey key, String eventType, Map<String, Object> event) {
        if (key == null || key.getWebhookUrl() == null || key.getWebhookUrl().isBlank()) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_type", eventType);
        body.put("timestamp", System.currentTimeMillis());
        body.put("tenant_id", key.getTenantId());
        body.putAll(event);
        String payload;
        try {
            payload = mapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("Webhook 序列化失败 tenant={} err={}", key.getTenantId(), e.getMessage());
            return;
        }
        String signature = key.getWebhookSecret() == null || key.getWebhookSecret().isBlank()
                ? "" : ApiSignature.hmacHex(key.getWebhookSecret(), payload);
        String url = key.getWebhookUrl();
        executor.submit(() -> {
            for (int i = 0; i < Math.max(1, maxRetry); i++) {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.APPLICATION_JSON);
                    if (!signature.isBlank()) headers.set("X-PTV-Signature", signature);
                    HttpEntity<String> entity = new HttpEntity<>(payload, headers);
                    restTemplate.postForEntity(UriComponentsBuilder.fromHttpUrl(url).build().toUri(), entity, String.class);
                    return;
                } catch (Exception e) {
                    log.warn("Webhook 重试 {}/{} url={} err={}", i + 1, maxRetry, url, e.getMessage());
                    try {
                        Thread.sleep(500L * (i + 1));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        });
    }
}