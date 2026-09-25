//! 178 维特征向量维度权威表（与 Java 端 `detection.FeatureSchema` 逐键对齐）。
//!
//! 分组计数：战斗 52 / 移动 44 / 环境设备 30 / JVM 20 / 模组 15 / 网络 17 = 178。
//! 本表是端侧采集、端侧降维与端云上报的唯一顺序来源；顺序即 `FeatureVector` 的下标序。

/// 维度分组。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Group {
    Combat,
    Movement,
    Environment,
    Jvm,
    Mod,
    Network,
}

/// 维度总数。
pub const DIM_COUNT: usize = 178;

/// 权威维度表：`(key, group)`，顺序固定。
pub const DIMS: [(&str, Group); DIM_COUNT] = [
    // ==== 战斗 52 维 ====
    ("feature_click_cps", Group::Combat),
    ("feature_click_interval_mean", Group::Combat),
    ("feature_click_interval_var", Group::Combat),
    ("feature_click_interval_cv", Group::Combat),
    ("feature_click_interval_skew", Group::Combat),
    ("feature_click_interval_kurt", Group::Combat),
    ("feature_click_burst_count", Group::Combat),
    ("feature_click_burst_ratio", Group::Combat),
    ("feature_killaura_angle_speed", Group::Combat),
    ("feature_killaura_mean", Group::Combat),
    ("feature_killaura_std", Group::Combat),
    ("feature_aim_smoothness", Group::Combat),
    ("feature_aim_snap_count", Group::Combat),
    ("feature_aim_snap_ratio", Group::Combat),
    ("feature_aim_target_attraction", Group::Combat),
    ("feature_aim_micro_jitter_entropy", Group::Combat),
    ("feature_jitter_entropy", Group::Combat),
    ("feature_aim_trajectory_curvature", Group::Combat),
    ("feature_trajectory_curvature", Group::Combat),
    ("feature_aim_bezier_fit_error", Group::Combat),
    ("feature_reach_distance", Group::Combat),
    ("feature_reach_distance_mean", Group::Combat),
    ("feature_reach_distance_max", Group::Combat),
    ("feature_criticals_rate", Group::Combat),
    ("feature_criticals_impossible", Group::Combat),
    ("feature_swing_animation_speed", Group::Combat),
    ("feature_hit_select_consistency", Group::Combat),
    ("feature_semantic_killaura", Group::Combat),
    ("feature_human_likeness", Group::Combat),
    ("feature_ai_pattern_anomaly", Group::Combat),
    ("feature_slow_accel_drift", Group::Combat),
    ("feature_attack_rate", Group::Combat),
    ("feature_attack_interval_cv", Group::Combat),
    ("feature_autoblock_ratio", Group::Combat),
    ("feature_autoblock_switch_time", Group::Combat),
    ("feature_fasteat_duration_mean", Group::Combat),
    ("feature_fasteat_interval_cv", Group::Combat),
    ("feature_cheststealer_items_per_sec", Group::Combat),
    ("feature_invmanager_ops_per_sec", Group::Combat),
    ("feature_autoarmor_equip_delay", Group::Combat),
    ("feature_swing_animation_cv", Group::Combat),
    ("feature_aim_rotation_jerk", Group::Combat),
    ("feature_aim_lock_time", Group::Combat),
    ("feature_aim_post_hit_correction", Group::Combat),
    ("feature_attack_pitch_mean", Group::Combat),
    ("feature_attack_pitch_std", Group::Combat),
    ("feature_criticals_airborne_ratio", Group::Combat),
    ("feature_killaura_target_switch_rate", Group::Combat),
    ("feature_target_lock_duration", Group::Combat),
    ("feature_combo_interval_cv", Group::Combat),
    ("feature_hit_select_entropy", Group::Combat),
    ("feature_aim_entropy", Group::Combat),
    // ==== 移动 44 维 ====
    ("feature_speed_ratio", Group::Movement),
    ("feature_speed_mean", Group::Movement),
    ("feature_speed_max", Group::Movement),
    ("feature_speed_variance", Group::Movement),
    ("feature_fly_vertical_speed", Group::Movement),
    ("feature_fly_sustain_time", Group::Movement),
    ("feature_nofall_violations", Group::Movement),
    ("feature_nofall_void", Group::Movement),
    ("feature_scaffold_block_per_sec", Group::Movement),
    ("feature_scaffold_sneak_consistency", Group::Movement),
    ("feature_fastplace_block_per_sec", Group::Movement),
    ("feature_fastbreak_block_per_sec", Group::Movement),
    ("feature_nuker_break_radius", Group::Movement),
    ("feature_velocity_horizontal", Group::Movement),
    ("feature_velocity_vertical", Group::Movement),
    ("feature_velocity_reduction_ratio", Group::Movement),
    ("feature_step_height", Group::Movement),
    ("feature_sprint_consistency", Group::Movement),
    ("feature_velocity_ratio", Group::Movement),
    ("feature_speed_accel", Group::Movement),
    ("feature_speed_jerk", Group::Movement),
    ("feature_ground_time_ratio", Group::Movement),
    ("feature_air_time_ratio", Group::Movement),
    ("feature_jump_frequency", Group::Movement),
    ("feature_jump_height_mean", Group::Movement),
    ("feature_strafe_ratio", Group::Movement),
    ("feature_omnisprint_violations", Group::Movement),
    ("feature_sprint_food_mismatch", Group::Movement),
    ("feature_step_violations", Group::Movement),
    ("feature_step_height_max", Group::Movement),
    ("feature_fly_hover_ratio", Group::Movement),
    ("feature_position_delta_per_tick_max", Group::Movement),
    ("feature_teleport_count", Group::Movement),
    ("feature_blink_position_jump", Group::Movement),
    ("feature_movement_correlation", Group::Movement),
    ("feature_movement_input_lag", Group::Movement),
    ("feature_path_smoothness", Group::Movement),
    ("feature_path_curvature", Group::Movement),
    ("feature_diagonal_speed_ratio", Group::Movement),
    ("feature_slowdown_ratio", Group::Movement),
    ("feature_collision_ignored_ratio", Group::Movement),
    ("feature_void_time", Group::Movement),
    ("feature_fall_distance_max", Group::Movement),
    ("feature_scaffold_pitch_angle", Group::Movement),
    // ==== 环境设备 30 维 ====
    ("feature_pcie_dma_present", Group::Environment),
    ("feature_iommu_disabled", Group::Environment),
    ("feature_hw_perf_counter_anomaly", Group::Environment),
    ("feature_unknown_kernel_drivers", Group::Environment),
    ("feature_ssdt_hooks", Group::Environment),
    ("feature_ghost_client_mem", Group::Environment),
    ("feature_remote_threads", Group::Environment),
    ("feature_non_image_mem_ratio", Group::Environment),
    ("feature_vm_detected", Group::Environment),
    ("feature_cpu_core_count", Group::Environment),
    ("feature_cpu_hypervisor_bit", Group::Environment),
    ("feature_total_memory_gb", Group::Environment),
    ("feature_free_memory_ratio", Group::Environment),
    ("feature_disk_free_ratio", Group::Environment),
    ("feature_disk_total_gb", Group::Environment),
    ("feature_system_uptime_hours", Group::Environment),
    ("feature_os_build_age_days", Group::Environment),
    ("feature_process_count", Group::Environment),
    ("feature_suspicious_process_count", Group::Environment),
    ("feature_signed_process_ratio", Group::Environment),
    ("feature_sandbox_indicator_count", Group::Environment),
    ("feature_sandbox_cpu_cores_low", Group::Environment),
    ("feature_debugger_present", Group::Environment),
    ("feature_hardware_breakpoint_count", Group::Environment),
    ("feature_hid_device_count", Group::Environment),
    ("feature_usb_device_count", Group::Environment),
    ("feature_screen_refresh_rate", Group::Environment),
    ("feature_gpu_vendor_known", Group::Environment),
    ("feature_system_model_virtual", Group::Environment),
    ("feature_thermal_throttle_ratio", Group::Environment),
    // ==== JVM 20 维 ====
    ("feature_jvm_heap_used_ratio", Group::Jvm),
    ("feature_jvm_heap_max_mb", Group::Jvm),
    ("feature_jvm_gc_count", Group::Jvm),
    ("feature_jvm_gc_time_ms", Group::Jvm),
    ("feature_jvm_thread_count", Group::Jvm),
    ("feature_jvm_peak_thread_count", Group::Jvm),
    ("feature_jvm_daemon_thread_ratio", Group::Jvm),
    ("feature_jvm_class_loaded_count", Group::Jvm),
    ("feature_jvm_class_unloaded_count", Group::Jvm),
    ("feature_jvm_jit_compile_time_ms", Group::Jvm),
    ("feature_jvm_uptime_sec", Group::Jvm),
    ("feature_jvm_cpu_load", Group::Jvm),
    ("feature_jvm_system_cpu_load", Group::Jvm),
    ("feature_jvm_available_processors", Group::Jvm),
    ("feature_jvm_file_descriptor_ratio", Group::Jvm),
    ("feature_jvm_safepoint_count", Group::Jvm),
    ("feature_jvm_safepoint_time_ms", Group::Jvm),
    ("feature_jvm_pending_finalization", Group::Jvm),
    ("feature_jvm_agent_attached", Group::Jvm),
    ("feature_jvm_input_args_count", Group::Jvm),
    // ==== 模组 15 维 ====
    ("feature_mod_count", Group::Mod),
    ("feature_mod_jar_count", Group::Mod),
    ("feature_mod_unsigned_ratio", Group::Mod),
    ("feature_mod_signature_valid_ratio", Group::Mod),
    ("feature_mod_unknown_count", Group::Mod),
    ("feature_mod_cheat_name_hits", Group::Mod),
    ("feature_mod_network_loader_count", Group::Mod),
    ("feature_mod_reflective_loader_count", Group::Mod),
    ("feature_mod_classloader_count", Group::Mod),
    ("feature_mod_bytecode_diff_ratio", Group::Mod),
    ("feature_mod_inject_detected", Group::Mod),
    ("feature_mod_fabric_present", Group::Mod),
    ("feature_mod_forge_present", Group::Mod),
    ("feature_mod_mixin_hook_count", Group::Mod),
    ("feature_mod_version_mismatch_count", Group::Mod),
    // ==== 网络 17 维 ====
    ("feature_packet_anomaly_ratio", Group::Network),
    ("feature_proxy_detected", Group::Network),
    ("feature_abnormal_latency", Group::Network),
    ("feature_net_interface_count", Group::Network),
    ("feature_net_mtu_mean", Group::Network),
    ("feature_net_speed_mean", Group::Network),
    ("feature_net_loopback_ratio", Group::Network),
    ("feature_net_virtual_ratio", Group::Network),
    ("feature_net_bytes_sent", Group::Network),
    ("feature_net_bytes_recv", Group::Network),
    ("feature_net_packet_rate", Group::Network),
    ("feature_net_jitter_ms", Group::Network),
    ("feature_net_rtt_ms", Group::Network),
    ("feature_net_retransmit_ratio", Group::Network),
    ("feature_net_upload_ratio", Group::Network),
    ("feature_net_down_ratio", Group::Network),
    ("feature_net_packet_loss_ratio", Group::Network),
];

/// 键 → 下标；未知键返回 `None`。
pub fn index_of(key: &str) -> Option<usize> {
    DIMS.iter().position(|(k, _)| *k == key)
}

/// 下标 → 键。
pub fn key_at(index: usize) -> Option<&'static str> {
    DIMS.get(index).map(|(k, _)| *k)
}

/// 中性默认值：这些键的合法默认非零（用于剔除占位，与 Java `NEUTRAL_DEFAULTS` 一致）。
pub const NEUTRAL_DEFAULTS: [(&str, f64); 2] = [
    ("feature_speed_ratio", 1.0),
    ("feature_velocity_ratio", 1.0),
];

/// 取某键的中性默认值（无则 0）。
pub fn neutral_default(key: &str) -> f64 {
    NEUTRAL_DEFAULTS
        .iter()
        .find(|(k, _)| *k == key)
        .map(|(_, v)| *v)
        .unwrap_or(0.0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn schema_shape_is_178_unique_keys() {
        assert_eq!(DIMS.len(), 178);
        let mut seen = HashSet::new();
        for (k, _) in DIMS.iter() {
            assert!(k.starts_with("feature_"), "非法键前缀: {k}");
            assert!(seen.insert(*k), "重复键: {k}");
        }
    }

    #[test]
    fn group_counts_match_design_doc() {
        let count = |g: Group| DIMS.iter().filter(|(_, x)| *x == g).count();
        assert_eq!(count(Group::Combat), 52);
        assert_eq!(count(Group::Movement), 44);
        assert_eq!(count(Group::Environment), 30);
        assert_eq!(count(Group::Jvm), 20);
        assert_eq!(count(Group::Mod), 15);
        assert_eq!(count(Group::Network), 17);
    }
}
