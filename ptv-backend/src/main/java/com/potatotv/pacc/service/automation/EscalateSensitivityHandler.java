package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.domain.automation.AutomationExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * §4.3.3 动作①：同一作弊家族 1 小时内检测数超阈值 → 自动升级该类型检测灵敏度。
 * <p>把 {@code detection_sensitivity} 倍数上调（上限 3.0），检测引擎读取该倍数提高采样/判定强度。</p>
 */
@Component
@RequiredArgsConstructor
public class EscalateSensitivityHandler implements AutomationActionHandler {

    static final double MAX_SENSITIVITY = 3.0;
    static final double MIN_SENSITIVITY = 0.1;

    private final AutomationStateService state;

    @Override
    public String code() {
        return "ESCALATE_SENSITIVITY";
    }

    @Override
    public AutomationActionResult execute(AutomationContext context) {
        double oldValue = state.detectionSensitivity();
        double target = oldValue <= 0 ? 1.5 : oldValue * 1.5;
        double next = Math.min(MAX_SENSITIVITY, Math.max(MIN_SENSITIVITY, target));
        if (next <= oldValue) {
            return AutomationActionResult.noop("sensitivity 已达上限 " + oldValue);
        }
        state.setNumber(AutomationStateService.KEY_SENSITIVITY, next, context.actor());
        return AutomationActionResult.applied("sensitivity " + oldValue + "->" + next);
    }

    @Override
    public AutomationActionResult revert(AutomationExecution execution) {
        double[] pair = AutomationDetails.arrow(execution.getDetail());
        if (Double.isNaN(pair[0])) {
            return AutomationActionResult.noop("无法解析回滚值");
        }
        state.setNumber(AutomationStateService.KEY_SENSITIVITY, pair[0], "automation-revert");
        return AutomationActionResult.applied("sensitivity 已回滚至 " + pair[0]);
    }
}