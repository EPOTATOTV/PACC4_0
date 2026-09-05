package com.potatotv.pacc.service;

import com.potatotv.pacc.domain.ConfidenceTier;

/**
 * v4.4 扫描强度自适应：按置信度等级、近期红屏状态与游戏场景动态调整扫描档位。
 * <p>目标：非战斗/低置信场景低频轻量扫描（省CPU），战斗/高置信场景高频深度扫描。</p>
 */
public class ScanIntensityService {

    /** 游戏场景（影响扫描频率与深度）。 */
    public enum Scenario {
        MENU, LOADING, NORMAL, COMBAT
    }

    /** 扫描档位。mode: FULL 全量 / INCREMENTAL 增量 / SNAPSHOT 快照。 */
    public record ScanProfile(String mode, long intervalMs, int depth) {
    }

    private static final long FULL_INTERVAL = 5000;
    private static final long INCREMENTAL_INTERVAL = 10000;
    private static final long SNAPSHOT_INTERVAL = 30000;

    /**
     * 计算扫描档位。
     * @param tier 置信度等级
     * @param recentRedscreen 近期是否触发红屏（true → 优先全量复查）
     * @param scenario 游戏场景
     */
    public ScanProfile profile(ConfidenceTier tier, boolean recentRedscreen, Scenario scenario) {
        String mode;
        long base;
        int depth;
        if (recentRedscreen || tier == ConfidenceTier.HIGH) {
            mode = "FULL";
            base = FULL_INTERVAL;
            depth = 3;
        } else if (tier == ConfidenceTier.MEDIUM) {
            mode = "INCREMENTAL";
            base = INCREMENTAL_INTERVAL;
            depth = 2;
        } else {
            mode = "SNAPSHOT";
            base = SNAPSHOT_INTERVAL;
            depth = 1;
        }
        double multiplier = scenario == null ? 1.0 : switch (scenario) {
            case COMBAT -> 0.5;   // 战斗更频繁（间隔减半）
            case NORMAL -> 1.0;
            case LOADING -> 2.0;
            case MENU -> 3.0;
        };
        long intervalMs = Math.max(2000, Math.round(base * multiplier));
        return new ScanProfile(mode, intervalMs, depth);
    }
}