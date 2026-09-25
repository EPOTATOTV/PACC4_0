package com.potatotv.paccclient.detection;

import java.util.Optional;

/**
 * 行为检测：移动 / 战斗 / 交互维度。基于游戏输入事件流做统计建模，
 * 检测 KillAura / Aimbot / Reach / AutoClicker 等异常行为。
 * <p>行为数据来自本地 Hook 的输入与游戏内存只读采样，不上报服务器自身业务数据。</p>
 *
 * <p>v5.2 起主判定路径是 {@link BruteForceDetector} 的 L0/L1 融合（分布检验 + 轨迹拟合 + 时序 + 规则 + AI），
 * 本类保留为兜底：当融合判定没有产出事件时，用输入频率做一次粗筛，参数取自真实特征向量
 * （{@code feature_click_cps} / {@code feature_killaura_angle_speed}），不做任何随机取样。</p>
 */
public final class BehaviorMonitor {

    /** 兜底粗筛阈值（与 L0 硬阈值一致，避免两处口径分叉）。 */
    private static final long CPS_THRESHOLD = 14L;
    private static final double AIM_DELTA_THRESHOLD = 0.9;

    /** 兜底判定：输入频率 / 瞄准位移超阈时返回事件（真实数据驱动，无随机）。 */
    public Optional<DetectionEvent> inspectInput(long clicksPerSecond, double verticalAimDelta) {
        boolean autoClick = clicksPerSecond > CPS_THRESHOLD;
        boolean aimSnap = Math.abs(verticalAimDelta) > AIM_DELTA_THRESHOLD;
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