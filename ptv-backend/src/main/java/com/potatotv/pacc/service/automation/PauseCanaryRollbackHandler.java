package com.potatotv.pacc.service.automation;

import com.potatotv.pacc.domain.automation.AutomationExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * §4.3.3 动作⑤：新客户端崩溃率超阈值（默认 1%）→ 暂停灰度并把生效版本回滚到上一个稳定版本。
 *
 * <p>写入 {@code canary_paused=true}，并把 {@code active_client_version} 切到
 * {@code rollback_version}（回滚目标由发布流程写入状态表）。回滚动作恢复暂停前状态与原版本。</p>
 */
@Component
@RequiredArgsConstructor
public class PauseCanaryRollbackHandler implements AutomationActionHandler {

    private final AutomationStateService state;

    @Override
    public String code() {
        return "PAUSE_CANARY_ROLLBACK";
    }

    @Override
    public AutomationActionResult execute(AutomationContext context) {
        boolean oldPaused = state.isCanaryPaused();
        if (oldPaused) {
            return AutomationActionResult.noop("灰度已处于暂停状态");
        }
        String fromVersion = state.get(AutomationStateService.KEY_ACTIVE_VERSION,
                context.metricString("clientVersion", ""));
        String targetVersion = state.get(AutomationStateService.KEY_ROLLBACK_VERSION,
                context.metricString("rollbackVersion", ""));
        state.setBool(AutomationStateService.KEY_CANARY_PAUSED, true, context.actor());
        if (!targetVersion.isBlank()) {
            state.put(AutomationStateService.KEY_ACTIVE_VERSION, targetVersion, context.actor());
        }
        double crashRate = context.metricDouble("crashRate", 0.0);
        return AutomationActionResult.applied("pausedOld=" + oldPaused + "; from=" + fromVersion
                + "; to=" + targetVersion + "; crashRate=" + crashRate + "; paused=true");
    }

    @Override
    public AutomationActionResult revert(AutomationExecution execution) {
        String detail = execution.getDetail();
        boolean oldPaused = AutomationDetails.boolToken(detail, "pausedOld", false);
        String fromVersion = AutomationDetails.token(detail, "from");
        state.setBool(AutomationStateService.KEY_CANARY_PAUSED, oldPaused, "automation-revert");
        if (fromVersion != null && !fromVersion.isBlank()) {
            state.put(AutomationStateService.KEY_ACTIVE_VERSION, fromVersion, "automation-revert");
        }
        return AutomationActionResult.applied("灰度状态已回滚：paused=" + oldPaused + "; version=" + fromVersion);
    }
}