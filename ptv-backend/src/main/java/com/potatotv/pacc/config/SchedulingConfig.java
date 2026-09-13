package com.potatotv.pacc.config;

import com.potatotv.pacc.service.AlertService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * v5.0 定时任务：告警规则引擎周期性评估。
 */
@Configuration
@EnableScheduling
@RequiredArgsConstructor
public class SchedulingConfig {

    private final AlertService alertService;

    /** 每分钟评估一次启用中的告警规则。 */
    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void evaluateAlerts() {
        try {
            alertService.evaluateRules();
        } catch (Exception e) {
            // 单轮评估失败不应中断调度
            var log = org.slf4j.LoggerFactory.getLogger(SchedulingConfig.class);
            log.warn("告警规则评估失败 err={}", e.getMessage());
        }
    }
}