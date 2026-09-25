package com.potatotv.pacc.service.automation;

/**
 * 后端负载探针：为 §4.3.3「负载过高自动降级」触发器提供当前 CPU 负载观测值。
 * <p>抽成接口便于在生产环境替换为外部监控源（Prometheus 等）或在单测中注入固定值。</p>
 */
public interface SystemLoadProvider {

    /** 当前 CPU 负载，取值 0.0 ~ 1.0（1.0 即满负载）；无法获取时返回 0.0。 */
    double cpuLoad();
}