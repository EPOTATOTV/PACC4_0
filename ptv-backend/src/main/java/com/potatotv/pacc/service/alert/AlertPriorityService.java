package com.potatotv.pacc.service.alert;

import org.springframework.stereotype.Component;

/**
 * §4.3.2 优先级排序：按严重度 + 聚合规模给出 P0~P3 优先级；低优先级走延迟批量通知。
 */
@Component
public class AlertPriorityService {

    /** 按严重度与聚合规模计算优先级（P0 最高）。 */
    public String priorityOf(int severity, int signalCount) {
        int score = Math.max(0, severity) * 10 + Math.min(40, Math.max(0, signalCount));
        if (score >= 40) {
            return "P0";
        }
        if (score >= 25) {
            return "P1";
        }
        if (score >= 12) {
            return "P2";
        }
        return "P3";
    }

    /** 是否低优先级（延迟批量通知）。 */
    public boolean isLowPriority(String priority) {
        return "P3".equals(priority);
    }
}