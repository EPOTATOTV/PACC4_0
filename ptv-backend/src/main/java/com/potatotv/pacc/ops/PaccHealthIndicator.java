package com.potatotv.pacc.ops;

import com.potatotv.pacc.service.OnlineStatusService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * P1：健康检查深化。在 Spring Boot Actuator /actuator/health 上叠加业务维度——
 * WSS 在线玩家数（检测引擎实时心跳的代理指标）。
 */
@Component
public class PaccHealthIndicator implements HealthIndicator {

    private final OnlineStatusService onlineStatusService;

    public PaccHealthIndicator(OnlineStatusService onlineStatusService) {
        this.onlineStatusService = onlineStatusService;
    }

    @Override
    public Health health() {
        long online;
        try {
            online = onlineStatusService.onlineCount();
        } catch (Exception e) {
            return Health.down().withDetail("players_online", "unavailable").build();
        }
        Health.Builder builder = Health.up().withDetail("players_online", online);
        // 检测引擎心跳异常则标记 DEGRADED（此处仅就绪信号；具体指标由 metrics 签名库补充）
        if (online < 0) {
            builder.status("DEGRADED");
        }
        return builder.build();
    }
}