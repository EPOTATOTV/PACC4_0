package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.domain.automation.AutomationExecution;

/**
 * §4.3.3 自动响应动作处理器 SPI：一个动作编码对应一个实现。
 *
 * <p>所有动作必须可审计（执行明细落 {@code t_automation_execution}）且可回滚
 * （{@link #revert(AutomationExecution)} 依据执行明细恢复原状）。</p>
 */
public interface AutomationActionHandler {

    /** 动作编码，对应 {@code AutomationRule#actionCode()}。 */
    String code();

    /** 执行动作。 */
    AutomationActionResult execute(AutomationContext context);

    /** 回滚动作：依据执行明细恢复执行前状态。 */
    AutomationActionResult revert(AutomationExecution execution);
}