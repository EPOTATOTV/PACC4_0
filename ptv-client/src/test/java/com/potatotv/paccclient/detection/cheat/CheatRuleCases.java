package com.potatotv.paccclient.detection.cheat;

import com.potatotv.paccclient.detection.AnalysisContext;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.analysis.ClickIntervalAnalyzer;
import com.potatotv.paccclient.detection.analysis.ClickIntervalAnalyzer.ClickAnalysis;
import com.potatotv.paccclient.detection.analysis.ClickIntervalAnalyzer.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * 作弊规则的等价性用例集（迁移到 PRL 前后共用同一张表）。
 *
 * <p>每条规则都至少覆盖「命中」「不命中」两类输入，外加跨阈值的边界值；特征只写该规则真正读的维度，
 * 其余维度按 {@link FeatureVector#get} 的默认 0.0 处理 —— 这既让用例可读，也顺带钉住「未设置的
 * 维度必须是 0」这个前提。{@link #all()} 的顺序是金标文件的行序，改顺序等于让金标全部失效。</p>
 */
final class CheatRuleCases {

    private CheatRuleCases() {
    }

    /** 一组输入。{@code clickVerdict} 只有 CriticalsRule 会读，{@code temporalAnomaly} 只有 AutoBlockRule 会读。 */
    record Case(String name, FeatureVector fv, Verdict clickVerdict, double temporalAnomaly) {

        AnalysisContext context() {
            ClickAnalysis click = clickVerdict == Verdict.INSUFFICIENT
                    ? ClickAnalysis.insufficient()
                    : new ClickAnalysis(120, 10, 0.08, 0, 0, 0, 0, clickVerdict, 40, 0, 0.0);
            return new AnalysisContext(click, null, temporalAnomaly, 0.0, List.of());
        }
    }

    /** 带点击判定 / 时序异常的用例；重载会与 {@link #of(String, Object...)} 撞，所以单独起个名。 */
    private static Case withContext(String name, Verdict verdict, double temporal, Object... featurePairs) {
        FeatureVector fv = new FeatureVector();
        for (int i = 0; i < featurePairs.length; i += 2) {
            fv.put((String) featurePairs[i], ((Number) featurePairs[i + 1]).doubleValue());
        }
        return new Case(name, fv, verdict, temporal);
    }

    private static Case of(String name, Object... featurePairs) {
        return withContext(name, Verdict.INSUFFICIENT, 0.0, featurePairs);
    }

    static List<Case> all() {
        List<Case> cases = new ArrayList<>();

        // ---- Scaffold：放置速率 / 潜行一致性 / 俯仰角三个加分项 ----
        cases.add(of("scaffold_全命中", "feature_scaffold_block_per_sec", 7.0,
                "feature_scaffold_sneak_consistency", 0.7, "feature_scaffold_pitch_angle", 80.0));
        cases.add(of("scaffold_仅速率", "feature_scaffold_block_per_sec", 4.0));
        cases.add(of("scaffold_速率不足", "feature_scaffold_block_per_sec", 0.5,
                "feature_scaffold_sneak_consistency", 0.9, "feature_scaffold_pitch_angle", 89.0));
        cases.add(of("scaffold_阈值边界3", "feature_scaffold_block_per_sec", 3.0,
                "feature_scaffold_sneak_consistency", 0.6, "feature_scaffold_pitch_angle", 75.0));

        // ---- 放置 / 破坏速率类 ----
        cases.add(of("fastplace_命中", "feature_fastplace_block_per_sec", 3.0));
        cases.add(of("fastplace_低于阈值", "feature_fastplace_block_per_sec", 1.0));
        cases.add(of("fastbreak_高速摆动稳定", "feature_fastbreak_block_per_sec", 6.0,
                "feature_swing_animation_cv", 0.05));
        cases.add(of("fastbreak_未达阈值", "feature_fastbreak_block_per_sec", 1.0,
                "feature_swing_animation_cv", 0.5));
        cases.add(of("nuker_大范围连破", "feature_nuker_break_radius", 6.0,
                "feature_fastbreak_block_per_sec", 6.0));
        cases.add(of("nuker_范围不足", "feature_nuker_break_radius", 1.0,
                "feature_fastbreak_block_per_sec", 6.0));
        cases.add(of("cheststealer_命中", "feature_cheststealer_items_per_sec", 12.0));
        cases.add(of("invmanager_命中", "feature_invmanager_ops_per_sec", 20.0));

        // ---- Criticals：唯一读点击判定的规则 ----
        cases.add(withContext("criticals_高可信点击", Verdict.HIGHLY_LIKELY, 0.0,
                "feature_criticals_impossible", 3.0, "feature_criticals_rate", 0.9,
                "feature_criticals_airborne_ratio", 0.95));
        cases.add(withContext("criticals_疑似点击", Verdict.SUSPECTED, 0.0,
                "feature_criticals_impossible", 1.0, "feature_criticals_rate", 0.5,
                "feature_criticals_airborne_ratio", 0.2));
        cases.add(withContext("criticals_正常点击", Verdict.NORMAL, 0.0,
                "feature_criticals_impossible", 2.0, "feature_criticals_rate", 0.9,
                "feature_criticals_airborne_ratio", 0.95));
        cases.add(withContext("criticals_无数据", Verdict.INSUFFICIENT, 0.0));

        // ---- 移动类 ----
        cases.add(of("velocity_高横向", "feature_velocity_horizontal", 0.7,
                "feature_velocity_reduction_ratio", 0.05));
        cases.add(of("velocity_低横向", "feature_velocity_horizontal", 0.1,
                "feature_velocity_reduction_ratio", 0.5));
        cases.add(of("nofall_多次违规加虚空", "feature_nofall_violations", 5.0,
                "feature_nofall_void", 1.0, "feature_void_time", 1.5));
        cases.add(of("nofall_仅低违规", "feature_nofall_violations", 1.0));
        cases.add(of("nofall_无数据", "feature_nofall_violations", 0.0));
        cases.add(of("step_超高台阶", "feature_step_height_max", 1.1, "feature_step_violations", 3.0));
        cases.add(of("step_未达阈值", "feature_step_height_max", 0.2, "feature_step_violations", 0.0));
        cases.add(of("sprint_全速违规", "feature_omnisprint_violations", 6.0,
                "feature_sprint_consistency", 0.995));
        cases.add(of("sprint_无违规", "feature_omnisprint_violations", 0.0));

        // ---- 战斗辅助：唯一读时序异常的规则 ----
        cases.add(withContext("autoblock_高比例极快切换", Verdict.INSUFFICIENT, 0.8,
                "feature_autoblock_ratio", 0.95, "feature_autoblock_switch_time", 20.0));
        cases.add(withContext("autoblock_无时序异常", Verdict.INSUFFICIENT, 0.0,
                "feature_autoblock_ratio", 0.95, "feature_autoblock_switch_time", 20.0));
        cases.add(withContext("autoblock_低比例", Verdict.INSUFFICIENT, 0.0,
                "feature_autoblock_ratio", 0.1, "feature_autoblock_switch_time", 400.0));

        // ---- 自动装备 / 快速进食 / 瞬移 ----
        cases.add(of("autoarmor_极快", "feature_autoarmor_equip_delay", 50.0));
        cases.add(of("autoarmor_中等", "feature_autoarmor_equip_delay", 200.0));
        cases.add(of("autoarmor_正常", "feature_autoarmor_equip_delay", 500.0));
        cases.add(of("autoarmor_无数据", "feature_autoarmor_equip_delay", 0.0));
        cases.add(of("fasteat_极快且规律", "feature_fasteat_duration_mean", 400.0,
                "feature_fasteat_interval_cv", 0.05));
        cases.add(of("fasteat_中速", "feature_fasteat_duration_mean", 1000.0,
                "feature_fasteat_interval_cv", 0.5));
        cases.add(of("fasteat_正常时长", "feature_fasteat_duration_mean", 1500.0));
        cases.add(of("blink_多次瞬移", "feature_teleport_count", 8.0, "feature_blink_position_jump", 70.0,
                "feature_net_rtt_ms", 50.0, "feature_net_packet_loss_ratio", 0.1));
        cases.add(of("blink_高丢包抵消", "feature_teleport_count", 8.0, "feature_blink_position_jump", 70.0,
                "feature_net_rtt_ms", 200.0, "feature_net_packet_loss_ratio", 0.5));
        cases.add(of("blink_低瞬移", "feature_teleport_count", 1.0, "feature_blink_position_jump", 0.0,
                "feature_net_rtt_ms", 30.0, "feature_net_packet_loss_ratio", 0.0));

        // ---- 汇总类：全零、以及多规则同时命中 ----
        cases.add(of("全零向量"));
        cases.add(withContext("多规则同时命中", Verdict.HIGHLY_LIKELY, 0.9,
                "feature_fastplace_block_per_sec", 5.0, "feature_cheststealer_items_per_sec", 20.0,
                "feature_autoarmor_equip_delay", 50.0, "feature_fasteat_duration_mean", 300.0,
                "feature_fasteat_interval_cv", 0.02, "feature_nofall_violations", 6.0,
                "feature_nofall_void", 1.0, "feature_void_time", 2.0,
                "feature_criticals_impossible", 4.0, "feature_criticals_rate", 0.9,
                "feature_criticals_airborne_ratio", 0.95,
                "feature_step_height_max", 1.2, "feature_step_violations", 4.0));

        // ---- §6.1 新增：内存注入类 ----
        cases.add(of("memory_region_非镜像内存与幽灵客户端",
                "feature_non_image_mem_ratio", 0.35, "feature_ghost_client_mem", 1.0));
        cases.add(of("memory_region_正常", "feature_non_image_mem_ratio", 0.05));
        cases.add(of("module_injection_反射与网络加载器",
                "feature_mod_inject_detected", 1.0, "feature_mod_reflective_loader_count", 2.0,
                "feature_mod_network_loader_count", 1.0));
        cases.add(of("module_injection_无注入"));

        // ---- §6.1 新增：系统级 ----
        cases.add(of("debugger_present_调试与Agent挂载",
                "feature_debugger_present", 1.0, "feature_jvm_agent_attached", 1.0));
        cases.add(of("debugger_present_无调试"));
        cases.add(of("injector_tool_可疑进程与远程线程",
                "feature_suspicious_process_count", 2.0, "feature_remote_threads", 3.0));
        cases.add(of("injector_tool_无注入工具"));
        cases.add(of("virtual_machine_虚拟化与沙箱",
                "feature_vm_detected", 1.0, "feature_system_model_virtual", 1.0,
                "feature_sandbox_indicator_count", 4.0));
        cases.add(of("virtual_machine_真实机", "feature_sandbox_indicator_count", 1.0));
        cases.add(of("kernel_hook_SSDT与未签名驱动",
                "feature_ssdt_hooks", 2.0, "feature_unknown_kernel_drivers", 3.0));
        cases.add(of("kernel_hook_无内核异常"));

        // ---- §6.1 新增：行为异常 ----
        cases.add(of("click_regularity_节拍器式点击",
                "feature_click_interval_cv", 0.02, "feature_click_interval_kurt", 0.5,
                "feature_click_interval_mean", 40.0));
        cases.add(of("click_regularity_人类点击", "feature_click_interval_cv", 0.6,
                "feature_click_interval_kurt", 1.0, "feature_click_interval_mean", 120.0));
        cases.add(of("trajectory_anomaly_直线平滑轨迹",
                "feature_movement_input_lag", 0.99, "feature_path_smoothness", 0.97,
                "feature_path_curvature", 0.005));
        cases.add(of("trajectory_anomaly_人类轨迹", "feature_movement_input_lag", 0.5,
                "feature_path_smoothness", 0.6, "feature_path_curvature", 0.05));
        cases.add(of("reaction_time_高锁定零修正",
                "feature_aim_lock_time", 0.95, "feature_aim_post_hit_correction", 0.01,
                "feature_attack_interval_cv", 0.05));
        cases.add(of("reaction_time_人类反应", "feature_aim_lock_time", 0.5,
                "feature_aim_post_hit_correction", 0.3, "feature_attack_interval_cv", 0.5));
        return cases;
    }
}