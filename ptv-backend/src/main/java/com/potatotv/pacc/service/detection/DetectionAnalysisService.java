package com.potatotv.pacc.service.detection;

import com.potatotv.pacc.domain.FeatureVector;
import com.potatotv.pacc.service.AiInferenceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * v4.1 检测分析编排：暴力外挂引擎 + 隐身外挂引擎 + AI 行为画像三层融合。
 * <p>供管理后台的「v4.1 检测分析」接口与运营使用。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DetectionAnalysisService {

    private final BruteForceCheatDetector bruteForceCheatDetector;
    private final StealthCheatDetector stealthCheatDetector;
    private final AiInferenceClient aiInferenceClient;

    /** 全量分析：暴力 + 隐身 + AI 画像。 */
    public Map<String, Object> analyze(FeatureVector fv, Map<String, Object> ctx) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("edition", ctx.getOrDefault("edition", "JAVA"));
        out.put("feature_dims", fv.size());

        List<BruteForceCheatDetector.Verdict> brute =
                bruteForceCheatDetector.evaluateAll(fv, ctx);
        out.put("brute_force", brute);

        List<StealthCheatDetector.Verdict> stealth =
                stealthCheatDetector.evaluateAll(fv, ctx);
        out.put("stealth", stealth);

        // AI 行为画像（128 维）
        var ai = aiInferenceClient.classifyBehavior(fv.asMap());
        out.put("ai_behavior", ai.orElse(null));
        var human = aiInferenceClient.humanize(trajectoryFrom(fv));
        out.put("ai_human_likeness", human.orElse(null));
        return out;
    }

    /** 从特征向量提取轨迹统计子集（供人类行为模拟度）。 */
    private Map<String, Double> trajectoryFrom(FeatureVector fv) {
        Map<String, Double> traj = new LinkedHashMap<>();
        copyIf(fv, traj, "feature_trajectory_curvature", "trajectory_curvature");
        copyIf(fv, traj, "feature_jitter_entropy", "jitter_entropy");
        copyIf(fv, traj, "feature_pause_ratio", "pause_ratio");
        copyIf(fv, traj, "feature_click_interval_cv", "click_interval_cv");
        return traj;
    }

    private void copyIf(FeatureVector fv, Map<String, Double> out, String src, String dst) {
        double v = fv.get(src);
        if (v != 0.0) out.put(dst, v);
    }

    /** 空分析上下文。 */
    public static Map<String, Object> emptyContext() {
        return new LinkedHashMap<>();
    }

    /** 常见作弊场景的演示特征向量（用于后台"一键检测演示"）。 */
    public static FeatureVector demoFeatureVector() {
        return new FeatureVector()
                .set("feature_killaura_angle_speed", 62.0)
                .set("feature_killaura_mean", 55.0)
                .set("feature_aim_smoothness", 0.02)
                .set("feature_semantic_killaura", 0.82)
                .set("feature_click_cps", 16.5)
                .set("feature_click_interval_var", 0.008)
                .set("feature_click_interval_cv", 0.03)
                .set("feature_reach_distance", 4.6)
                .set("feature_fly_vertical_speed", 1.8)
                .set("feature_speed_ratio", 1.9)
                .set("feature_pcie_dma_present", 1.0)
                .set("feature_iommu_disabled", 1.0)
                .set("feature_ghost_client_mem", 1.0)
                .set("feature_human_likeness", 0.12)
                .set("feature_aim_target_attraction", 0.85)
                .set("feature_trajectory_curvature", 0.98)
                .set("feature_jitter_entropy", 0.4)
                .set("feature_pause_ratio", 0.02)
                .set("feature_click_interval_cv", 0.03);
    }

    /** 人类正常行为演示特征。 */
    public static FeatureVector humanFeatureVector() {
        return new FeatureVector()
                .set("feature_killaura_angle_speed", 12.0)
                .set("feature_killaura_mean", 10.0)
                .set("feature_aim_smoothness", 0.4)
                .set("feature_semantic_killaura", 0.05)
                .set("feature_click_cps", 6.0)
                .set("feature_click_interval_var", 0.3)
                .set("feature_click_interval_cv", 0.3)
                .set("feature_reach_distance", 3.0)
                .set("feature_fly_vertical_speed", 0.1)
                .set("feature_speed_ratio", 1.0)
                .set("feature_pcie_dma_present", 0.0)
                .set("feature_iommu_disabled", 0.0)
                .set("feature_ghost_client_mem", 0.0)
                .set("feature_human_likeness", 0.85)
                .set("feature_aim_target_attraction", 0.1)
                .set("feature_trajectory_curvature", 0.55)
                .set("feature_jitter_entropy", 3.2)
                .set("feature_pause_ratio", 0.35)
                .set("feature_click_interval_cv", 0.28);
    }
}