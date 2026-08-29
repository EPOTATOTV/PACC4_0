package com.potatotv.paccclient.detection;

import java.util.Optional;

/**
 * v4.1 暴力外挂检测器（端侧信号层/时序层）：
 * 采集战斗/移动行为特征，构建 128 维特征向量，并做轻量端侧预判定。
 * 语义层由 PTV AI 行为画像完成（XGBoost/LSTM 替代实现）。
 */
public final class BruteForceDetector {

    /**
     * 采样一次行为特征并生成特征向量（战斗 + 移动维度）。
     *
     * @param clicksPerSecond   点击频率（Hz）
     * @param verticalAimDelta  瞄准垂直位移（弧度/秒）
     * @param horizontalSpeed   水平移动速度（方块/秒）
     * @param verticalSpeed     垂直移动速度（方块/秒）
     * @param attackDistance    攻击距离（方块）
     */
    public FeatureVector sample(double clicksPerSecond, double verticalAimDelta,
                                double horizontalSpeed, double verticalSpeed, double attackDistance) {
        FeatureVector fv = new FeatureVector();
        // ---- 战斗特征（前 52 维中的代表维度） ----
        fv.put("feature_click_cps", clamp(clicksPerSecond, 0, 30));
        fv.put("feature_click_interval_var", 0.1);      // 端侧估算
        fv.put("feature_click_interval_cv", 0.1);
        fv.put("feature_killaura_angle_speed", Math.abs(verticalAimDelta) * 60.0);
        fv.put("feature_killaura_mean", Math.abs(verticalAimDelta) * 50.0);
        fv.put("feature_aim_smoothness", 0.5 - Math.min(0.45, Math.abs(verticalAimDelta)));
        fv.put("feature_semantic_killaura", 0.0);       // 语义层由 PTV 填充
        fv.put("feature_reach_distance", clamp(attackDistance, 0, 8));
        fv.put("feature_criticals_rate", 0.0);
        // ---- 移动特征（44 维中的代表维度） ----
        fv.put("feature_speed_ratio", clamp(horizontalSpeed / 5.6, 0, 3)); // 基岩/Java 平均 5.6 方块/秒
        fv.put("feature_fly_vertical_speed", clamp(verticalSpeed, 0, 5));
        fv.put("feature_velocity_ratio", 1.0);
        fv.put("feature_nofall_violations", 0);
        fv.put("feature_scaffold_block_per_sec", 0);
        fv.put("feature_fastplace_block_per_sec", 0);
        fv.put("feature_fastbreak_block_per_sec", 0);
        fv.put("feature_nuker_break_radius", 0);
        return fv;
    }

    /** 端侧预判定：信号层超限即高风险。 */
    public Optional<DetectionEvent> inspect(FeatureVector fv) {
        double cps = fv.get("feature_click_cps");
        double angle = fv.get("feature_killaura_angle_speed");
        double speed = fv.get("feature_speed_ratio");
        double fly = fv.get("feature_fly_vertical_speed");
        double reach = fv.get("feature_reach_distance");

        boolean autoClick = cps > 14;
        boolean killaura = angle > 45;
        boolean speedHack = speed > 1.5;
        boolean flyHack = fly > 1.2;
        boolean reachHack = reach > 4.0;

        int risk = (autoClick ? 55 : 0) + (killaura ? 55 : 0) + (speedHack ? 50 : 0)
                + (flyHack ? 65 : 0) + (reachHack ? 50 : 0);
        if (risk <= 0) {
            return Optional.empty();
        }
        String type = firstOf(autoClick ? "autoclicker" : null,
                killaura ? "killaura" : null,
                flyHack ? "fly" : null,
                speedHack ? "speed" : null,
                reachHack ? "reach" : null);
        String severity = risk >= 110 ? "high" : "medium";
        return Optional.of(new DetectionEvent(type, severity, Math.min(100, risk),
                "javaw.exe", null, null, "win10_x64", fv.toDetailJson()));
    }

    private static String firstOf(String... candidates) {
        for (String c : candidates) {
            if (c != null) return c;
        }
        return "behavior";
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
