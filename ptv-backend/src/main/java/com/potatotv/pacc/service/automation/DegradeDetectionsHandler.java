package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.domain.automation.AutomationExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * §4.3.3 动作④：后端负载超阈值（默认 80%）→ 降低上报频率并关闭非关键检测。
 *
 * <p>写入的是「其他服务真实读取的开关」而非日志：
 * {@code degraded_mode=true}、{@code non_critical_detection_enabled=false}、
 * {@code report_interval_multiplier} 翻倍（上限 8）。回滚时按明细中的前值逐一还原。</p>
 */
@Component
@RequiredArgsConstructor
public class DegradeDetectionsHandler implements AutomationActionHandler {

    private static final double MAX_REPORT_INTERVAL = 8.0;

    private final AutomationStateService state;

    @Override
    public String code() {
        return "DEGRADE_DETECTIONS";
    }

    @Override
    public AutomationActionResult execute(AutomationContext context) {
        boolean oldDegraded = state.isDegradedMode();
        boolean oldNonCritical = state.isNonCriticalDetectionEnabled();
        double oldInterval = state.reportIntervalMultiplier();
        double nextInterval = Math.min(MAX_REPORT_INTERVAL, oldInterval * 2);
        if (oldDegraded && !oldNonCritical && nextInterval <= oldInterval) {
            return AutomationActionResult.noop("已处于降级上限");
        }
        state.setBool(AutomationStateService.KEY_DEGRADED_MODE, true, context.actor());
        state.setBool(AutomationStateService.KEY_NON_CRITICAL_DETECTION, false, context.actor());
        state.setNumber(AutomationStateService.KEY_REPORT_INTERVAL, nextInterval, context.actor());
        double load = context.metricDouble("load", 0.0);
        return AutomationActionResult.applied("degOld=" + oldDegraded + "; ncOld=" + oldNonCritical
                + "; riOld=" + oldInterval + "; load=" + load
                + "; degNew=true; ncNew=false; riNew=" + nextInterval);
    }

    @Override
    public AutomationActionResult revert(AutomationExecution execution) {
        String detail = execution.getDetail();
        boolean oldDegraded = AutomationDetails.boolToken(detail, "degOld", false);
        boolean oldNonCritical = AutomationDetails.boolToken(detail, "ncOld", true);
        double oldInterval = AutomationDetails.numToken(detail, "riOld", 1.0);
        state.setBool(AutomationStateService.KEY_DEGRADED_MODE, oldDegraded, "automation-revert");
        state.setBool(AutomationStateService.KEY_NON_CRITICAL_DETECTION, oldNonCritical, "automation-revert");
        state.setNumber(AutomationStateService.KEY_REPORT_INTERVAL, oldInterval, "automation-revert");
        return AutomationActionResult.applied("降级状态已回滚：degraded=" + oldDegraded
                + "; nonCritical=" + oldNonCritical + "; reportInterval=" + oldInterval);
    }
}