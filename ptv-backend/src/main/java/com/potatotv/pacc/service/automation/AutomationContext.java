package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.domain.automation.AutomationRule;

import java.util.Map;

/**
 * §4.3.3 动作执行上下文：动作处理器据此决定具体行为（如创建检测器、调整灵敏度）。
 *
 * @param rule    触发的规则（含阈值/窗口）
 * @param metrics 触发时观测到的指标快照（如 family / count / rate / load）
 * @param actor   触发者（scheduler 或管理员身份）
 */
public record AutomationContext(AutomationRule rule, Map<String, Object> metrics, String actor) {

    public String actionCode() {
        return rule.getActionCode();
    }

    public double threshold() {
        return rule.getThreshold();
    }

    public Object metric(String key, Object defaultValue) {
        return metrics.getOrDefault(key, defaultValue);
    }

    public String metricString(String key, String defaultValue) {
        Object v = metrics.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    public double metricDouble(String key, double defaultValue) {
        Object v = metrics.get(key);
        return v instanceof Number n ? n.doubleValue() : defaultValue;
    }
}