package com.potatotv.pacc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.domain.RedscreenAlert;
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
 * Webhook 推送：红屏警告 / 查端完成事件实时推送第三方系统。
 * 基于 Java 21 虚拟线程实现高并发异步推送。
 */
@Slf4j
@Service
public class WebhookDispatcher {

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    @Value("${pacc.webhook.retry:3}")
    private int maxRetry;

    /** Webhook 目标 URL（演示从系统属性/环境读取，生产走配置中心）。 */
    private String target() {
        String u = System.getenv("PACC_WEBHOOK_URL");
        return (u == null || u.isBlank()) ? "" : u;
    }

    public void onRedscreen(RedscreenAlert alert, int queuePosition) {
        if (target().isEmpty()) return; // 未配置则跳过
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_type", "redscreen_alert");
        body.put("alert_id", alert.getAlertId());
        body.put("level", alert.getLevel());
        body.put("cheat_type", alert.getCheatType());
        body.put("pteid_masked", alert.getPteidMasked());
        body.put("timestamp", alert.getOccurredAt().toString());
        body.put("risk_score", alert.getRiskScore());
        body.put("game_edition", alert.getEdition());
        body.put("admin_action_required", true);
        fire(body);
    }

    public void onInspectDone(String sessionId, String pteid, String conclusion) {
        if (target().isEmpty()) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event_type", "inspect_done");
        body.put("session_id", sessionId);
        body.put("pteid_masked", RedscreenService.mask(pteid));
        body.put("conclusion", conclusion);
        fire(body);
    }

    private void fire(Map<String, Object> body) {
        executor.submit(() -> {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            for (int i = 0; i < maxRetry; i++) {
                try {
                    HttpEntity<String> entity = new HttpEntity<>(mapper.writeValueAsString(body), headers);
                    restTemplate.postForEntity(UriComponentsBuilder.fromHttpUrl(target()).build().toUri(), entity, String.class);
                    return;
                } catch (Exception e) {
                    log.warn("Webhook 推送重试 {}/{} err={}", i + 1, maxRetry, e.getMessage());
                    try { Thread.sleep(500L * (i + 1)); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
            }
        });
    }
}