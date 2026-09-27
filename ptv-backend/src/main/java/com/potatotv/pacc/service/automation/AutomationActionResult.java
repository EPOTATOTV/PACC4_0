package com.potatotv.pacc.service.automation;

/**
 * §4.3.3 动作执行结果。
 *
 * @param applied    是否实际生效
 * @param reversible 是否可回滚
 * @param detail     执行明细（同时作为回滚依据，形如 {@code sensitivity 1.0->1.5}）
 */
public record AutomationActionResult(boolean applied, boolean reversible, String detail) {

    public static AutomationActionResult applied(String detail) {
        return new AutomationActionResult(true, true, detail);
    }

    public static AutomationActionResult noop(String detail) {
        return new AutomationActionResult(false, false, detail);
    }
}