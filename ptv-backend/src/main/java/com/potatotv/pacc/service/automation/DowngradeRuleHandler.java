package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.domain.AlertEvent;
import com.potatotv.pacc.domain.automation.AutomationExecution;
import com.potatotv.pacc.repository.AlertEventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * §4.3.3 动作③：误报成功率超阈值（默认 20%）→ 自动降级检测规则并告警。
 * <p>把 {@code detection_sensitivity} 倍数下调（下限 0.1），同时落一条告警事件供运营复核；
 * 回滚时恢复灵敏度并把该告警置为 RESOLVED。</p>
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("null") // 仓储 null 分析误报
public class DowngradeRuleHandler implements AutomationActionHandler {

    private final AutomationStateService state;
    private final AlertEventRepository alertEventRepository;

    @Override
    public String code() {
        return "DOWNGRADE_RULE_ALERT";
    }

    @Override
    public AutomationActionResult execute(AutomationContext context) {
        double oldValue = state.detectionSensitivity();
        double next = Math.max(EscalateSensitivityHandler.MIN_SENSITIVITY, oldValue * 0.5);
        if (next >= oldValue) {
            return AutomationActionResult.noop("sensitivity 已达下限 " + oldValue);
        }
        state.setNumber(AutomationStateService.KEY_SENSITIVITY, next, context.actor());
        double rate = context.metricDouble("misreportRate", 0.0);
        AlertEvent alert = alertEventRepository.save(AlertEvent.builder()
                .id("ae_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                .ruleId(context.rule().getCode())
                .ruleName("自动化：误报率过高，检测灵敏度已降级")
                .severity(3)
                .metric("misreport_rate")
                .conditionValue(String.format("%.4f", rate))
                .threshold((int) Math.round(context.threshold() * 100))
                .actualValue((float) (rate * 100))
                .status("FIRING")
                .firedAt(Instant.now())
                .build());
        return AutomationActionResult.applied("sensitivity " + oldValue + "->" + next + "; alert=" + alert.getId());
    }

    @Override
    public AutomationActionResult revert(AutomationExecution execution) {
        double[] pair = AutomationDetails.arrow(execution.getDetail());
        boolean restored = !Double.isNaN(pair[0]);
        if (restored) {
            state.setNumber(AutomationStateService.KEY_SENSITIVITY, pair[0], "automation-revert");
        }
        String alertId = AutomationDetails.token(execution.getDetail(), "alert");
        if (alertId != null) {
            alertEventRepository.findById(alertId).ifPresent(a -> {
                a.setStatus("RESOLVED");
                a.setResolvedAt(Instant.now());
                a.setResolutionNote("自动化回滚：灵敏度已恢复");
                alertEventRepository.save(a);
            });
        }
        if (!restored && alertId == null) {
            return AutomationActionResult.noop("无法解析回滚值");
        }
        return AutomationActionResult.applied("sensitivity 已回滚至 " + pair[0] + "; alert=" + alertId);
    }
}