package com.potatotv.paccclient.detection;

import java.util.Optional;

/**
 * 行为检测：移动 / 战斗 / 交互维度。基于游戏输入事件流做统计建模，
 * 检测 KillAura / Aimbot / Reach / AutoClicker 等异常行为。
 * <p>说明：行为数据来自本地 Hook 的输入与游戏内存只读采样，不上报服务器自身业务数据。</p>
 */
public final class BehaviorMonitor {

    /** 演示：基于输入事件频率的简化统计判定。 */
    public Optional<DetectionEvent> inspectInput(long clicksPerSecond, double verticalAimDelta) {
        boolean autoClick = clicksPerSecond > 14;
        boolean aimSnap = Math.abs(verticalAimDelta) > 0.9;
        if (autoClick || aimSnap) {
            int risk = (autoClick ? 60 : 0) + (aimSnap ? 60 : 0);
            return Optional.of(new DetectionEvent(
                    autoClick ? "autoclicker" : "aimbot",
                    risk >= 110 ? "high" : "medium",
                    Math.min(100, risk),
                    "javaw.exe", null, null, "win10_x64"));
        }
        return Optional.empty();
    }
}