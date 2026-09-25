//! PACC 特征采集（设计文档 §2.2 的 `pacc-collector`）。
//!
//! 一次 `Collector::collect()` 产出完整 178 维 [`FeatureVector`]，语义与 Java 端
//! `detection.FeatureCollector` 对齐：战斗/移动组由增量输入事件流建模，环境设备组
//! 由 [`Platform`] 只读采样，JVM/模组组在无数据源时保持中性默认值（不虚增覆盖度）。
//!
//! 缺失维度一律填 0 或中性默认值，保证任意时刻 `FeatureVector` 长度恒为 178，
//! 且 `coverage()` 只统计**有真实数据源支撑**的维度数。

pub mod schema;
pub mod stats;

use schema::{index_of, neutral_default, DIM_COUNT};
use stats::*;

const BASE_SPEED: f64 = 5.6;
const BURST_INTERVAL_MS: u64 = 30;
const TELEPORT_DISTANCE: f64 = 8.0;
const STEP_MIN_RISE: f64 = 0.6;
const FALL_DAMAGE_HEIGHT: f64 = 3.0;
/// 输入/位置缓冲上限（滑动窗口规模，与 Java 端 `BufferedInputSource` 同量级）。
const BUFFER_CAP: usize = 512;

/// 178 维行为特征向量（`feature_` 键 → f64）。
#[derive(Debug, Clone)]
pub struct FeatureVector {
    values: [f64; DIM_COUNT],
}

impl FeatureVector {
    /// 全零向量。
    pub fn zeros() -> Self {
        Self {
            values: [0.0; DIM_COUNT],
        }
    }

    /// 中性向量：所有 `NEUTRAL_DEFAULTS` 键取其中性值，其余为 0。
    pub fn neutral() -> Self {
        let mut fv = Self::zeros();
        for (key, val) in schema::NEUTRAL_DEFAULTS {
            fv.set(key, val);
        }
        fv
    }

    /// 按键取值；未知键返回 0。
    pub fn get(&self, key: &str) -> f64 {
        index_of(key).map(|i| self.values[i]).unwrap_or(0.0)
    }

    /// 按下标取值。
    pub fn get_idx(&self, index: usize) -> f64 {
        self.values.get(index).copied().unwrap_or(0.0)
    }

    /// 按键写入；未知键忽略。
    pub fn set(&mut self, key: &str, value: f64) {
        if let Some(i) = index_of(key) {
            self.values[i] = if value.is_finite() { value } else { 0.0 };
        }
    }

    /// 按下标写入。
    pub fn set_idx(&mut self, index: usize, value: f64) {
        if let Some(slot) = self.values.get_mut(index) {
            *slot = if value.is_finite() { value } else { 0.0 };
        }
    }

    /// 只读切片（模型推理入参）。
    pub fn as_slice(&self) -> &[f64; DIM_COUNT] {
        &self.values
    }

    /// 有效维度数：非零且不等于中性默认值的维度。
    pub fn non_placeholder_count(&self) -> usize {
        schema::DIMS
            .iter()
            .enumerate()
            .filter(|(i, (k, _))| {
                let v = self.values[*i];
                v != 0.0 && v != neutral_default(k)
            })
            .count()
    }
}

impl Default for FeatureVector {
    fn default() -> Self {
        Self::neutral()
    }
}

/// 点击/挥臂等输入事件。
#[derive(Debug, Clone, Copy)]
pub struct InputEvent {
    pub ts_millis: u64,
}

/// 攻击采样。
#[derive(Debug, Clone, Copy)]
pub struct AttackSample {
    pub ts_millis: u64,
    pub distance: f64,
    pub pitch: f64,
    pub yaw: f64,
    pub target_id: i32,
    pub critical: bool,
    pub airborne: bool,
    pub blocked: bool,
    pub hit: bool,
}

/// 位置采样（含速度与状态）。
#[derive(Debug, Clone, Copy)]
pub struct PositionSample {
    pub ts_millis: u64,
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub vx: f64,
    pub vy: f64,
    pub vz: f64,
    pub on_ground: bool,
    pub sprinting: bool,
    pub fall_damage_taken: bool,
}

/// 方块操作采样。
#[derive(Debug, Clone, Copy)]
pub struct BlockSample {
    pub ts_millis: u64,
    pub place: bool,
    pub sneaking: bool,
    pub face_angle: f64,
    pub radius: f64,
}

/// 交互动作类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ActionKind {
    Eat,
    Chest,
    Inventory,
    Armor,
}

/// 交互动作采样。
#[derive(Debug, Clone, Copy)]
pub struct ActionSample {
    pub ts_millis: u64,
    pub kind: ActionKind,
    pub duration_millis: f64,
    pub amount: f64,
}

fn push_capped<T>(buf: &mut Vec<T>, item: T) {
    if buf.len() >= BUFFER_CAP {
        buf.remove(0);
    }
    buf.push(item);
}

/// 增量特征采集器。
#[derive(Debug, Default)]
pub struct Collector {
    clicks: Vec<u64>,
    swings: Vec<u64>,
    attacks: Vec<AttackSample>,
    positions: Vec<PositionSample>,
    blocks: Vec<BlockSample>,
    actions: Vec<ActionSample>,
    trajectory: Vec<(f64, f64)>,
    aim_target: Option<(f64, f64)>,
    last_coverage: usize,
}

impl Collector {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn record_click(&mut self, ts_millis: u64) {
        push_capped(&mut self.clicks, ts_millis);
    }

    pub fn record_swing(&mut self, ts_millis: u64) {
        push_capped(&mut self.swings, ts_millis);
    }

    pub fn record_attack(&mut self, sample: AttackSample) {
        push_capped(&mut self.attacks, sample);
    }

    pub fn record_position(&mut self, sample: PositionSample) {
        push_capped(&mut self.positions, sample);
    }

    pub fn record_block(&mut self, sample: BlockSample) {
        push_capped(&mut self.blocks, sample);
    }

    pub fn record_action(&mut self, sample: ActionSample) {
        push_capped(&mut self.actions, sample);
    }

    /// 记录鼠标轨迹点（屏幕像素坐标）。
    pub fn record_mouse(&mut self, x: f64, y: f64) {
        push_capped(&mut self.trajectory, (x, y));
    }

    pub fn set_aim_target(&mut self, target: Option<(f64, f64)>) {
        self.aim_target = target;
    }

    /// 上一帧有真实数据支撑的维度数。
    pub fn coverage(&self) -> usize {
        self.last_coverage
    }

    /// 当前滑动窗口内的点击间隔（毫秒），供上层构造行为分析上下文。
    pub fn click_intervals(&self) -> Vec<u64> {
        intervals(&self.clicks)
    }

    /// 当前滑动窗口内的鼠标轨迹点数（0/1 表示无可用轨迹）。
    pub fn trajectory_len(&self) -> usize {
        self.trajectory.len()
    }

    /// 清空滑动窗口（如切局/停采样）。
    pub fn reset(&mut self) {
        self.clicks.clear();
        self.swings.clear();
        self.attacks.clear();
        self.positions.clear();
        self.blocks.clear();
        self.actions.clear();
        self.trajectory.clear();
        self.aim_target = None;
        self.last_coverage = 0;
    }

    /// 采集一帧完整 178 维特征向量。
    pub fn collect(&mut self) -> FeatureVector {
        let mut fv = FeatureVector::neutral();
        let mut backed = vec![false; DIM_COUNT];

        self.collect_combat(&mut fv, &mut backed);
        self.collect_movement(&mut fv, &mut backed);

        self.last_coverage = backed.iter().filter(|b| **b).count();
        fv
    }

    /// 记录一个「有真实数据源」的维度。
    fn put(fv: &mut FeatureVector, backed: &mut [bool], key: &str, value: f64) {
        if let Some(i) = index_of(key) {
            fv.set_idx(i, value);
            backed[i] = true;
        }
    }

    // ------------------------------ 战斗组 ------------------------------

    fn collect_combat(&mut self, fv: &mut FeatureVector, backed: &mut [bool]) {
        let clicks = self.clicks.clone();
        let swings = self.swings.clone();
        let attacks = self.attacks.clone();

        let click_intervals = intervals(&clicks);
        if !clicks.is_empty() {
            let last = *clicks.last().unwrap();
            let cps = clicks.iter().filter(|t| **t + 1000 >= last).count() as f64;
            Self::put(fv, backed, "feature_click_cps", cps);
        }
        if click_intervals.len() >= 2 {
            let arr: Vec<f64> = click_intervals.iter().map(|v| *v as f64).collect();
            Self::put(fv, backed, "feature_click_interval_mean", mean(&arr));
            Self::put(fv, backed, "feature_click_interval_var", variance(&arr));
            Self::put(fv, backed, "feature_click_interval_cv", cv(&arr));
            Self::put(fv, backed, "feature_click_interval_skew", skewness(&arr));
            Self::put(fv, backed, "feature_click_interval_kurt", kurtosis(&arr));
        }
        if !click_intervals.is_empty() {
            let burst = click_intervals
                .iter()
                .filter(|i| **i < BURST_INTERVAL_MS)
                .count();
            Self::put(fv, backed, "feature_click_burst_count", burst as f64);
            Self::put(
                fv,
                backed,
                "feature_click_burst_ratio",
                burst as f64 / click_intervals.len() as f64,
            );
        }
        if !swings.is_empty() {
            let first = *swings.first().unwrap();
            let last = *swings.last().unwrap();
            let span = ((last - first) as f64 / 1000.0).max(1.0);
            Self::put(
                fv,
                backed,
                "feature_swing_animation_speed",
                swings.len() as f64 / span,
            );
            let sw = intervals(&swings);
            if sw.len() >= 2 {
                let arr: Vec<f64> = sw.iter().map(|v| *v as f64).collect();
                Self::put(fv, backed, "feature_swing_animation_cv", cv(&arr));
            }
        }

        self.collect_aim(fv, backed);
        self.collect_attacks(fv, backed, &attacks);
        self.collect_interactions(fv, backed);
    }

    fn collect_aim(&mut self, fv: &mut FeatureVector, backed: &mut [bool]) {
        let traj = self.trajectory.clone();
        if traj.len() < 2 {
            return;
        }
        let rmse = poly2_fit_rmse(&traj);
        let curvature = mean_curvature(&traj);
        Self::put(fv, backed, "feature_aim_bezier_fit_error", rmse);
        Self::put(fv, backed, "feature_aim_trajectory_curvature", curvature);
        Self::put(fv, backed, "feature_trajectory_curvature", curvature);

        let mut curvature_changes = Vec::with_capacity(traj.len());
        for i in 2..traj.len() {
            let (px, py) = traj[i - 2];
            let (cx, cy) = traj[i - 1];
            let (nx, ny) = traj[i];
            curvature_changes.push(discrete_curvature(px, py, cx, cy, nx, ny));
        }
        let smooth = if curvature_changes.len() < 2 {
            1.0
        } else {
            clamp01(1.0 - rms_second_difference(&curvature_changes) / std::f64::consts::PI)
        };
        Self::put(fv, backed, "feature_aim_smoothness", smooth);

        let snaps = count_snaps(&traj);
        Self::put(fv, backed, "feature_aim_snap_count", snaps as f64);
        Self::put(
            fv,
            backed,
            "feature_aim_snap_ratio",
            snaps as f64 / (traj.len() - 1) as f64,
        );
        let dir_entropy = direction_entropy(&traj);
        Self::put(fv, backed, "feature_aim_entropy", dir_entropy);
        let jitter = micro_jitter_entropy(&traj);
        Self::put(fv, backed, "feature_aim_micro_jitter_entropy", jitter);
        Self::put(fv, backed, "feature_jitter_entropy", jitter);
        Self::put(fv, backed, "feature_human_likeness", clamp01(jitter / 3.5));
        Self::put(
            fv,
            backed,
            "feature_aim_rotation_jerk",
            third_difference_rms(&traj),
        );

        if let Some((tx, ty)) = self.aim_target {
            let (lx, ly) = traj[traj.len() - 1];
            let (fx, fy) = traj[0];
            let attraction = cosine(tx - lx, ty - ly, lx - fx, ly - fy);
            Self::put(fv, backed, "feature_aim_target_attraction", attraction);
        }
    }

    fn collect_attacks(
        &mut self,
        fv: &mut FeatureVector,
        backed: &mut [bool],
        attacks: &[AttackSample],
    ) {
        if attacks.is_empty() {
            return;
        }
        let first = attacks[0].ts_millis;
        let last = attacks[attacks.len() - 1].ts_millis;
        let span = ((last.saturating_sub(first)) as f64 / 1000.0).max(1e-3);

        let deltas = aim_deltas(attacks);
        if !deltas.is_empty() {
            let m = mean(&deltas);
            Self::put(fv, backed, "feature_killaura_mean", m);
            Self::put(fv, backed, "feature_killaura_std", std_dev(&deltas));
            let dt_sum: f64 = (1..attacks.len())
                .map(|i| {
                    (attacks[i]
                        .ts_millis
                        .saturating_sub(attacks[i - 1].ts_millis))
                    .max(1) as f64
                })
                .sum();
            let angle_speed = if dt_sum <= 0.0 {
                0.0
            } else {
                deltas.iter().sum::<f64>() * 1000.0 / dt_sum
            };
            Self::put(fv, backed, "feature_killaura_angle_speed", angle_speed);
            let snaps = deltas.iter().filter(|d| **d > 30.0).count();
            Self::put(fv, backed, "feature_aim_snap_count", snaps as f64);
            let sem = clamp01((m / 180.0 + snaps as f64 / deltas.len() as f64) / 2.0);
            Self::put(fv, backed, "feature_semantic_killaura", sem);
        }

        let distances: Vec<f64> = attacks.iter().map(|a| a.distance).collect();
        let pitches: Vec<f64> = attacks.iter().map(|a| a.pitch).collect();
        let criticals = attacks.iter().filter(|a| a.critical).count();
        let impossible = attacks.iter().filter(|a| a.critical && !a.airborne).count();
        let airborne_criticals = attacks.iter().filter(|a| a.critical && a.airborne).count();
        let hits = attacks.iter().filter(|a| a.hit).count();
        let blocked = attacks.iter().filter(|a| a.blocked).count();

        Self::put(
            fv,
            backed,
            "feature_reach_distance",
            attacks[attacks.len() - 1].distance,
        );
        Self::put(fv, backed, "feature_reach_distance_mean", mean(&distances));
        Self::put(fv, backed, "feature_reach_distance_max", max_of(&distances));
        Self::put(
            fv,
            backed,
            "feature_criticals_rate",
            criticals as f64 / attacks.len() as f64,
        );
        Self::put(
            fv,
            backed,
            "feature_criticals_impossible",
            impossible as f64,
        );
        Self::put(
            fv,
            backed,
            "feature_criticals_airborne_ratio",
            if criticals == 0 {
                0.0
            } else {
                airborne_criticals as f64 / criticals as f64
            },
        );
        Self::put(fv, backed, "feature_attack_pitch_mean", mean(&pitches));
        Self::put(fv, backed, "feature_attack_pitch_std", std_dev(&pitches));
        let attack_rate = attacks
            .iter()
            .filter(|a| a.ts_millis + 1000 >= last)
            .count() as f64;
        Self::put(fv, backed, "feature_attack_rate", attack_rate);
        Self::put(
            fv,
            backed,
            "feature_hit_select_consistency",
            hits as f64 / attacks.len() as f64,
        );
        Self::put(
            fv,
            backed,
            "feature_autoblock_ratio",
            blocked as f64 / attacks.len() as f64,
        );
        if deltas.len() >= 3 {
            Self::put(fv, backed, "feature_slow_accel_drift", trend_slope(&deltas));
        }

        let attack_intervals = intervals(&attacks.iter().map(|a| a.ts_millis).collect::<Vec<_>>());
        if attack_intervals.len() >= 2 {
            let arr: Vec<f64> = attack_intervals.iter().map(|v| *v as f64).collect();
            Self::put(fv, backed, "feature_attack_interval_cv", cv(&arr));
        }
        let combo: Vec<f64> = attack_intervals
            .iter()
            .filter(|i| **i < 200)
            .map(|i| *i as f64)
            .collect();
        if combo.len() >= 2 {
            Self::put(fv, backed, "feature_combo_interval_cv", cv(&combo));
        }

        let runs = target_runs(attacks);
        if !runs.is_empty() {
            let max_run = runs.iter().copied().max().unwrap_or(0);
            let sum: usize = runs.iter().sum();
            Self::put(
                fv,
                backed,
                "feature_aim_lock_time",
                max_run as f64 / attacks.len() as f64,
            );
            let mean_interval = if attack_intervals.is_empty() {
                0.25
            } else {
                mean(
                    &attack_intervals
                        .iter()
                        .map(|v| *v as f64)
                        .collect::<Vec<_>>(),
                ) / 1000.0
            };
            Self::put(
                fv,
                backed,
                "feature_target_lock_duration",
                (sum as f64 / runs.len() as f64) * mean_interval,
            );
            Self::put(
                fv,
                backed,
                "feature_hit_select_entropy",
                target_entropy(attacks),
            );
            Self::put(
                fv,
                backed,
                "feature_killaura_target_switch_rate",
                runs.len().saturating_sub(1) as f64 / span,
            );
        }

        // 命中后视角回正修正量。
        let mut correction_sum = 0.0;
        let mut correction_count = 0usize;
        for i in 1..attacks.len() {
            if attacks[i - 1].hit {
                correction_sum += (attacks[i].yaw - attacks[i - 1].yaw).abs()
                    + (attacks[i].pitch - attacks[i - 1].pitch).abs();
                correction_count += 1;
            }
        }
        if correction_count > 0 {
            Self::put(
                fv,
                backed,
                "feature_aim_post_hit_correction",
                correction_sum / correction_count as f64,
            );
        }
    }

    fn collect_interactions(&mut self, fv: &mut FeatureVector, backed: &mut [bool]) {
        let actions = self.actions.clone();
        if actions.is_empty() {
            return;
        }
        let first = actions[0].ts_millis;
        let last = actions[actions.len() - 1].ts_millis;
        let span = ((last.saturating_sub(first)) as f64 / 1000.0).max(1.0);

        let mut eat_durations = Vec::new();
        let mut eat_times = Vec::new();
        let mut chest_items = 0.0;
        let mut chest_count = 0usize;
        let mut inv_ops = 0usize;
        let mut armor_delay = 0.0;
        let mut armor_count = 0usize;
        for a in &actions {
            match a.kind {
                ActionKind::Eat => {
                    eat_durations.push(a.duration_millis.max(0.0));
                    eat_times.push(a.ts_millis);
                }
                ActionKind::Chest => {
                    chest_count += 1;
                    chest_items += a.amount.max(0.0);
                }
                ActionKind::Inventory => inv_ops += 1,
                ActionKind::Armor => {
                    armor_delay += a.duration_millis.max(0.0);
                    armor_count += 1;
                }
            }
        }
        if !eat_durations.is_empty() {
            Self::put(
                fv,
                backed,
                "feature_fasteat_duration_mean",
                mean(&eat_durations),
            );
            let ei = intervals(&eat_times);
            if ei.len() >= 2 {
                let arr: Vec<f64> = ei.iter().map(|v| *v as f64).collect();
                Self::put(fv, backed, "feature_fasteat_interval_cv", cv(&arr));
            }
        }
        if chest_count > 0 {
            Self::put(
                fv,
                backed,
                "feature_cheststealer_items_per_sec",
                chest_items / span,
            );
        }
        if inv_ops > 0 {
            Self::put(
                fv,
                backed,
                "feature_invmanager_ops_per_sec",
                inv_ops as f64 / span,
            );
        }
        if armor_count > 0 {
            Self::put(
                fv,
                backed,
                "feature_autoarmor_equip_delay",
                armor_delay / armor_count as f64,
            );
        }
    }

    // ------------------------------ 移动组 ------------------------------

    fn collect_movement(&mut self, fv: &mut FeatureVector, backed: &mut [bool]) {
        let positions = self.positions.clone();
        if positions.len() >= 2 {
            self.collect_positions(fv, backed, &positions);
        }
        self.collect_blocks(fv, backed);
    }

    fn collect_positions(
        &mut self,
        fv: &mut FeatureVector,
        backed: &mut [bool],
        positions: &[PositionSample],
    ) {
        let first = positions[0].ts_millis;
        let last = positions[positions.len() - 1].ts_millis;
        let span = ((last.saturating_sub(first)) as f64 / 1000.0).max(1e-3);

        let steps = positions.len() - 1;
        let mut speeds = Vec::with_capacity(steps);
        let mut accels = Vec::with_capacity(steps);
        let mut air_speed = 0.0;
        let mut ground_speed = 0.0;
        let mut air_count = 0usize;
        let mut ground_count = 0usize;
        let mut max_delta = 0.0f64;
        let mut teleports = 0usize;
        let mut path_len = 0.0f64;
        let mut max_vertical_air = 0.0f64;
        let mut fall_distance_max = 0.0f64;
        let mut nofall_violations = 0usize;
        let mut void_samples = 0usize;
        let mut void_time = 0.0f64;
        let mut hover_count = 0usize;
        let mut sprint_moving = 0usize;
        let mut moving_samples = 0usize;
        let mut jump_height_sum = 0.0f64;
        let mut jump_runs = 0usize;
        let mut step_height = 0.0f64;
        let mut step_violations = 0usize;
        let mut max_air_run = 0.0f64;
        let mut in_air_run = false;
        let mut air_run_start_y = 0.0f64;
        let mut max_air_run_y = 0.0f64;
        let mut air_run_start_ts = 0u64;
        let mut prev_speed: Option<f64> = None;
        let mut velocity_sum = 0.0;
        let mut vertical_velocity_sum = 0.0;

        for i in 1..positions.len() {
            let a = positions[i - 1];
            let b = positions[i];
            let dt = (b.ts_millis.saturating_sub(a.ts_millis)).max(1) as f64 / 1000.0;
            let dx = b.x - a.x;
            let dz = b.z - a.z;
            let dy = b.y - a.y;
            let horiz = (dx * dx + dz * dz).sqrt();
            let speed = horiz / dt;
            speeds.push(speed);
            let delta = (horiz * horiz + dy * dy).sqrt();
            path_len += delta;
            max_delta = max_delta.max(delta);
            if delta > TELEPORT_DISTANCE {
                teleports += 1;
            }
            if horiz > 0.01 {
                moving_samples += 1;
                if b.sprinting {
                    sprint_moving += 1;
                }
            }
            if b.on_ground {
                ground_count += 1;
                ground_speed += speed;
                if !a.on_ground && in_air_run {
                    let run_height = max_air_run_y - air_run_start_y.min(b.y);
                    jump_height_sum += (max_air_run_y - air_run_start_y).max(0.0);
                    jump_runs += 1;
                    if run_height > FALL_DAMAGE_HEIGHT && !b.fall_damage_taken {
                        nofall_violations += 1;
                    }
                    fall_distance_max = fall_distance_max.max(run_height);
                    max_air_run = max_air_run.max((b.ts_millis - air_run_start_ts) as f64 / 1000.0);
                    in_air_run = false;
                }
                if dy > STEP_MIN_RISE && dy <= 1.5 {
                    step_height = step_height.max(dy);
                }
                if dy > STEP_MIN_RISE && horiz > 0.05 {
                    step_violations += 1;
                }
            } else {
                air_count += 1;
                air_speed += speed;
                max_vertical_air = max_vertical_air.max(dy.abs() / dt);
                if b.vy.abs() < 0.05 {
                    hover_count += 1;
                }
                if !in_air_run {
                    in_air_run = true;
                    air_run_start_y = a.y;
                    air_run_start_ts = a.ts_millis;
                    max_air_run_y = b.y;
                } else {
                    max_air_run_y = max_air_run_y.max(b.y);
                }
            }
            if b.y < 0.0 {
                void_samples += 1;
                void_time += dt;
            }
            velocity_sum += (b.vx * b.vx + b.vz * b.vz).sqrt();
            vertical_velocity_sum += b.vy;
            if let Some(ps) = prev_speed {
                accels.push((speed - ps) / dt);
            }
            prev_speed = Some(speed);
        }

        if steps == 0 {
            return;
        }
        let mean_speed = mean(&speeds);
        Self::put(fv, backed, "feature_speed_ratio", mean_speed / BASE_SPEED);
        Self::put(fv, backed, "feature_speed_mean", mean_speed);
        Self::put(fv, backed, "feature_speed_max", max_of(&speeds));
        Self::put(fv, backed, "feature_speed_variance", variance(&speeds));
        if !accels.is_empty() {
            Self::put(fv, backed, "feature_speed_accel", mean(&accels));
        }
        Self::put(
            fv,
            backed,
            "feature_speed_jerk",
            rms_second_difference(&speeds),
        );
        Self::put(
            fv,
            backed,
            "feature_ground_time_ratio",
            ground_count as f64 / positions.len() as f64,
        );
        Self::put(
            fv,
            backed,
            "feature_air_time_ratio",
            air_count as f64 / positions.len() as f64,
        );
        Self::put(fv, backed, "feature_fly_vertical_speed", max_vertical_air);
        Self::put(fv, backed, "feature_fly_sustain_time", max_air_run);
        Self::put(
            fv,
            backed,
            "feature_fly_hover_ratio",
            if air_count == 0 {
                0.0
            } else {
                hover_count as f64 / air_count as f64
            },
        );
        Self::put(fv, backed, "feature_fall_distance_max", fall_distance_max);
        Self::put(
            fv,
            backed,
            "feature_nofall_violations",
            nofall_violations as f64,
        );
        Self::put(fv, backed, "feature_nofall_void", void_samples as f64);
        Self::put(fv, backed, "feature_void_time", void_time);
        Self::put(
            fv,
            backed,
            "feature_velocity_horizontal",
            velocity_sum / positions.len() as f64,
        );
        Self::put(
            fv,
            backed,
            "feature_velocity_vertical",
            vertical_velocity_sum / positions.len() as f64,
        );
        Self::put(fv, backed, "feature_position_delta_per_tick_max", max_delta);
        Self::put(fv, backed, "feature_teleport_count", teleports as f64);
        Self::put(fv, backed, "feature_blink_position_jump", max_delta);
        Self::put(fv, backed, "feature_step_height", step_height);
        Self::put(fv, backed, "feature_step_height_max", step_height);
        Self::put(
            fv,
            backed,
            "feature_step_violations",
            step_violations as f64,
        );
        Self::put(
            fv,
            backed,
            "feature_jump_frequency",
            jump_runs as f64 / span,
        );
        Self::put(
            fv,
            backed,
            "feature_jump_height_mean",
            if jump_runs == 0 {
                0.0
            } else {
                jump_height_sum / jump_runs as f64
            },
        );
        Self::put(
            fv,
            backed,
            "feature_sprint_consistency",
            if moving_samples == 0 {
                0.0
            } else {
                sprint_moving as f64 / moving_samples as f64
            },
        );
        Self::put(
            fv,
            backed,
            "feature_collision_ignored_ratio",
            if ground_count == 0 || ground_speed <= 0.0 {
                0.0
            } else {
                (air_speed / air_count.max(1) as f64) / (ground_speed / ground_count as f64)
            },
        );
        Self::put(
            fv,
            backed,
            "feature_movement_input_lag",
            if path_len <= 0.0 {
                1.0
            } else {
                let dx = positions[positions.len() - 1].x - positions[0].x;
                let dz = positions[positions.len() - 1].z - positions[0].z;
                (dx * dx + dz * dz).sqrt() / path_len
            },
        );
    }

    fn collect_blocks(&mut self, fv: &mut FeatureVector, backed: &mut [bool]) {
        let blocks = self.blocks.clone();
        if blocks.is_empty() {
            return;
        }
        let first = blocks[0].ts_millis;
        let last = blocks[blocks.len() - 1].ts_millis;
        let span = ((last.saturating_sub(first)) as f64 / 1000.0).max(1.0);

        let mut place_times = Vec::new();
        let mut break_times = Vec::new();
        let mut sneak_places = 0usize;
        let mut max_radius = 0.0f64;
        for b in &blocks {
            if b.place {
                place_times.push(b.ts_millis);
                if b.sneaking {
                    sneak_places += 1;
                }
            } else {
                break_times.push(b.ts_millis);
                max_radius = max_radius.max(b.radius);
            }
        }
        if !place_times.is_empty() {
            Self::put(
                fv,
                backed,
                "feature_scaffold_block_per_sec",
                place_times.len() as f64 / span,
            );
            Self::put(
                fv,
                backed,
                "feature_fastplace_block_per_sec",
                max_per_second(&place_times) as f64,
            );
            Self::put(
                fv,
                backed,
                "feature_scaffold_sneak_consistency",
                sneak_places as f64 / place_times.len() as f64,
            );
        }
        if !break_times.is_empty() {
            Self::put(
                fv,
                backed,
                "feature_fastbreak_block_per_sec",
                max_per_second(&break_times) as f64,
            );
            Self::put(fv, backed, "feature_nuker_break_radius", max_radius);
        }
    }
}

/// 环境设备组：由 [`Platform`] 只读采样填充。无能力/无数据的维度保持中性默认。
pub fn environment_features(platform: &dyn pacc_platform::Platform) -> FeatureVector {
    let mut fv = FeatureVector::neutral();
    let cores = std::thread::available_parallelism()
        .map(|n| n.get() as f64)
        .unwrap_or(0.0);
    fv.set("feature_cpu_core_count", cores);
    fv.set(
        "feature_sandbox_cpu_cores_low",
        if cores > 0.0 && cores < 4.0 { 1.0 } else { 0.0 },
    );

    let mut sandbox_indicators = 0.0;
    if cores > 0.0 && cores < 4.0 {
        sandbox_indicators += 1.0;
    }

    if let Ok(procs) = platform.enumerate_processes() {
        fv.set("feature_process_count", procs.len() as f64);
        let suspicious = procs
            .iter()
            .filter(|p| is_suspicious_process(&p.name))
            .count();
        fv.set("feature_suspicious_process_count", suspicious as f64);
        if !procs.is_empty() {
            fv.set(
                "feature_signed_process_ratio",
                1.0 - suspicious as f64 / procs.len() as f64,
            );
            // 内存层代理指标：匿名可执行映像在 procfs 加载器里会单独上报，这里只统计无映像进程。
            let non_image = procs.iter().filter(|p| p.exe.is_none()).count();
            fv.set(
                "feature_non_image_mem_ratio",
                non_image as f64 / procs.len() as f64,
            );
        }
    }

    if let Ok(debug) = platform.anti_debug_probe() {
        fv.set(
            "feature_debugger_present",
            if debug.debugger_present { 1.0 } else { 0.0 },
        );
        if debug.debugger_present {
            sandbox_indicators += 1.0;
        }
    }

    #[cfg(target_os = "linux")]
    fill_linux_env(&mut fv, &mut sandbox_indicators);

    fv.set("feature_sandbox_indicator_count", sandbox_indicators);
    fv
}

/// Linux 专有环境维度（内存/磁盘/运行时长/网络接口）。
#[cfg(target_os = "linux")]
fn fill_linux_env(fv: &mut FeatureVector, sandbox_indicators: &mut f64) {
    use std::fs;

    if let Ok(text) = fs::read_to_string("/proc/meminfo") {
        let mut total_kb = 0.0;
        let mut avail_kb = 0.0;
        for line in text.lines() {
            if let Some(v) = line.strip_prefix("MemTotal:") {
                total_kb = v
                    .trim()
                    .trim_end_matches(" kB")
                    .trim()
                    .parse()
                    .unwrap_or(0.0);
            } else if let Some(v) = line.strip_prefix("MemAvailable:") {
                avail_kb = v
                    .trim()
                    .trim_end_matches(" kB")
                    .trim()
                    .parse()
                    .unwrap_or(0.0);
            }
        }
        if total_kb > 0.0 {
            fv.set("feature_total_memory_gb", total_kb / 1024.0 / 1024.0);
            fv.set("feature_free_memory_ratio", avail_kb / total_kb);
            if total_kb / 1024.0 / 1024.0 < 4.0 {
                *sandbox_indicators += 1.0;
            }
        }
    }
    if let Ok(text) = fs::read_to_string("/proc/uptime") {
        if let Some(secs) = text
            .split_whitespace()
            .next()
            .and_then(|v| v.parse::<f64>().ok())
        {
            fv.set("feature_system_uptime_hours", secs / 3600.0);
            if secs < 600.0 {
                *sandbox_indicators += 1.0;
            }
        }
    }
    if let Ok(text) = fs::read_to_string("/proc/cpuinfo") {
        if text.contains("hypervisor") {
            fv.set("feature_cpu_hypervisor_bit", 1.0);
            fv.set("feature_vm_detected", 1.0);
        }
    }
}

/// 已知可疑/调试类进程名（命中即计入 `feature_suspicious_process_count`）。
fn is_suspicious_process(name: &str) -> bool {
    const BAD: [&str; 14] = [
        "cheatengine",
        "x64dbg",
        "x32dbg",
        "ollydbg",
        "processhacker",
        "processhacker2",
        "frida-server",
        "frida-helper",
        "gdb",
        "lldb",
        "gcore",
        "radare2",
        "rizin",
        "ghidra",
    ];
    let lower = name.to_ascii_lowercase();
    BAD.iter().any(|b| lower.contains(b))
}

fn intervals(ts: &[u64]) -> Vec<u64> {
    let mut out = Vec::with_capacity(ts.len().saturating_sub(1));
    for i in 1..ts.len() {
        out.push(ts[i].saturating_sub(ts[i - 1]).max(1));
    }
    out
}

fn aim_deltas(attacks: &[AttackSample]) -> Vec<f64> {
    if attacks.len() < 2 {
        return Vec::new();
    }
    let mut out = Vec::with_capacity(attacks.len() - 1);
    for i in 1..attacks.len() {
        let mut d_yaw = (attacks[i].yaw - attacks[i - 1].yaw).abs() % 360.0;
        if d_yaw > 180.0 {
            d_yaw = 360.0 - d_yaw;
        }
        let d_pitch = (attacks[i].pitch - attacks[i - 1].pitch).abs();
        out.push((d_yaw * d_yaw + d_pitch * d_pitch).sqrt());
    }
    out
}

fn target_runs(attacks: &[AttackSample]) -> Vec<usize> {
    let mut runs = Vec::new();
    let mut run = 0usize;
    let mut prev = i32::MIN;
    for a in attacks {
        if a.target_id == prev {
            run += 1;
        } else {
            if run > 0 {
                runs.push(run);
            }
            run = 1;
            prev = a.target_id;
        }
    }
    if run > 0 {
        runs.push(run);
    }
    runs
}

fn target_entropy(attacks: &[AttackSample]) -> f64 {
    let mut ids: Vec<i32> = attacks.iter().map(|a| a.target_id).collect();
    ids.sort_unstable();
    let mut counts: Vec<(i32, usize)> = Vec::new();
    for id in ids {
        match counts.last_mut() {
            Some((k, c)) if *k == id => *c += 1,
            _ => counts.push((id, 1)),
        }
    }
    let p: Vec<f64> = counts
        .iter()
        .map(|(_, c)| *c as f64 / attacks.len() as f64)
        .collect();
    shannon_entropy(&p)
}

/// 轨迹方向熵（8 个方向的香农熵）。
fn direction_entropy(traj: &[(f64, f64)]) -> f64 {
    const BINS: usize = 8;
    let mut hist = [0usize; BINS];
    let mut n = 0usize;
    for i in 1..traj.len() {
        let dx = traj[i].0 - traj[i - 1].0;
        let dy = traj[i].1 - traj[i - 1].1;
        if (dx * dx + dy * dy).sqrt() < 1e-6 {
            continue;
        }
        let ang = dy.atan2(dx);
        let b = (((ang + std::f64::consts::PI) / (2.0 * std::f64::consts::PI) * BINS as f64).floor()
            as isize)
            .rem_euclid(BINS as isize) as usize;
        hist[b] += 1;
        n += 1;
    }
    if n == 0 {
        return 0.0;
    }
    let p: Vec<f64> = hist.iter().map(|c| *c as f64 / n as f64).collect();
    shannon_entropy(&p)
}

/// 微抖动位移直方图熵（人类 > 2.5）。
fn micro_jitter_entropy(traj: &[(f64, f64)]) -> f64 {
    const BINS: usize = 8;
    if traj.len() < 3 {
        return 0.0;
    }
    let mut mags = Vec::with_capacity(traj.len() - 2);
    let mut max = 0.0_f64;
    for i in 1..traj.len() - 1 {
        let ax = (traj[i - 1].0 + traj[i + 1].0) / 2.0;
        let ay = (traj[i - 1].1 + traj[i + 1].1) / 2.0;
        let m = ((traj[i].0 - ax).powi(2) + (traj[i].1 - ay).powi(2)).sqrt();
        max = max.max(m);
        mags.push(m);
    }
    if max <= 0.0 {
        return 0.0;
    }
    let mut hist = [0usize; BINS];
    for m in &mags {
        let b = ((m / max * BINS as f64).floor() as usize).min(BINS - 1);
        hist[b] += 1;
    }
    let p: Vec<f64> = hist.iter().map(|c| *c as f64 / mags.len() as f64).collect();
    shannon_entropy(&p)
}

/// 轨迹三阶差分均方根（y 轴），近似加加速度。
fn third_difference_rms(traj: &[(f64, f64)]) -> f64 {
    if traj.len() < 4 {
        return 0.0;
    }
    let h: Vec<f64> = traj.iter().map(|p| p.1).collect();
    let mut se = 0.0;
    let mut n = 0usize;
    for i in 3..h.len() {
        let third = h[i] - 3.0 * h[i - 1] + 3.0 * h[i - 2] - h[i - 3];
        se += third * third;
        n += 1;
    }
    if n == 0 {
        0.0
    } else {
        (se / n as f64).sqrt()
    }
}

/// 相邻点最大角速度超过阈值的次数（瞬移）。
fn count_snaps(traj: &[(f64, f64)]) -> usize {
    let mut snaps = 0;
    for i in 1..traj.len() {
        let dx = traj[i].0 - traj[i - 1].0;
        let dy = traj[i].1 - traj[i - 1].1;
        let ang = dy.atan2(dx).abs().to_degrees();
        if ang > 60.0 {
            snaps += 1;
        }
    }
    snaps
}

/// 平均曲率（三点法）。
fn mean_curvature(traj: &[(f64, f64)]) -> f64 {
    if traj.len() < 3 {
        return 0.0;
    }
    let mut sum = 0.0;
    let mut n = 0usize;
    for i in 2..traj.len() {
        sum += discrete_curvature(
            traj[i - 2].0,
            traj[i - 2].1,
            traj[i - 1].0,
            traj[i - 1].1,
            traj[i].0,
            traj[i].1,
        );
        n += 1;
    }
    if n == 0 {
        0.0
    } else {
        sum / n as f64
    }
}

/// 二次多项式最小二乘拟合的 RMSE（贝塞尔拟合误差的轻量替代）。
fn poly2_fit_rmse(traj: &[(f64, f64)]) -> f64 {
    let n = traj.len();
    if n < 3 {
        return 0.0;
    }
    // 自变量 t 归一化到 [0,1]，避免数值病态。
    let x: Vec<f64> = (0..n).map(|i| i as f64 / (n - 1) as f64).collect();
    let (wx, bx, cx) = fit_quadratic(&x, &traj.iter().map(|p| p.0).collect::<Vec<_>>());
    let (wy, by, cy) = fit_quadratic(&x, &traj.iter().map(|p| p.1).collect::<Vec<_>>());
    let mut se = 0.0;
    for (i, t) in x.iter().enumerate() {
        let px = wx * t * t + bx * t + cx;
        let py = wy * t * t + by * t + cy;
        se += (traj[i].0 - px).powi(2) + (traj[i].1 - py).powi(2);
    }
    (se / n as f64).sqrt()
}

/// 对 `(t, v)` 做 y = a t² + b t + c 最小二乘拟合，返回 `(a, b, c)`。
fn fit_quadratic(t: &[f64], v: &[f64]) -> (f64, f64, f64) {
    let n = t.len() as f64;
    let (mut s1, mut s2, mut s3, mut s4) = (0.0, 0.0, 0.0, 0.0);
    let (mut y0, mut y1, mut y2) = (0.0, 0.0, 0.0);
    for i in 0..t.len() {
        let ti = t[i];
        let ti2 = ti * ti;
        s1 += ti;
        s2 += ti2;
        s3 += ti2 * ti;
        s4 += ti2 * ti2;
        y0 += v[i];
        y1 += v[i] * ti;
        y2 += v[i] * ti2;
    }
    // 解正规方程 3x3（克莱姆法则）。
    let m = [[s4, s3, s2], [s3, s2, s1], [s2, s1, n]];
    let rhs = [y2, y1, y0];
    let det = det3(&m);
    if det.abs() < 1e-12 {
        return (0.0, 0.0, mean(v));
    }
    let a = det3(&[
        [rhs[0], m[0][1], m[0][2]],
        [rhs[1], m[1][1], m[1][2]],
        [rhs[2], m[2][1], m[2][2]],
    ]) / det;
    let b = det3(&[
        [m[0][0], rhs[0], m[0][2]],
        [m[1][0], rhs[1], m[1][2]],
        [m[2][0], rhs[2], m[2][2]],
    ]) / det;
    let c = det3(&[
        [m[0][0], m[0][1], rhs[0]],
        [m[1][0], m[1][1], rhs[1]],
        [m[2][0], m[2][1], rhs[2]],
    ]) / det;
    (a, b, c)
}

fn det3(m: &[[f64; 3]; 3]) -> f64 {
    m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1])
        - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0])
        + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
}

/// 线性趋势斜率绝对值（缓慢漂移量）。
fn trend_slope(xs: &[f64]) -> f64 {
    let n = xs.len() as f64;
    let (mut sx, mut sy, mut sxx, mut sxy) = (0.0, 0.0, 0.0, 0.0);
    for (i, v) in xs.iter().enumerate() {
        let x = i as f64;
        sx += x;
        sy += v;
        sxx += x * x;
        sxy += x * v;
    }
    let denom = n * sxx - sx * sx;
    if denom.abs() < 1e-9 {
        0.0
    } else {
        ((n * sxy - sx * sy) / denom).abs()
    }
}

/// 任意 1 秒窗口内最大事件数。
fn max_per_second(times: &[u64]) -> usize {
    let mut best = 0usize;
    for i in 0..times.len() {
        let c = times[i..]
            .iter()
            .take_while(|t| **t < times[i] + 1000)
            .count();
        best = best.max(c);
    }
    best
}

#[cfg(test)]
mod tests {
    use super::*;
    use pacc_platform::{Capabilities, Platform};

    struct Dummy;
    impl Platform for Dummy {
        fn name(&self) -> &'static str {
            "dummy"
        }
        fn capabilities(&self) -> Capabilities {
            Capabilities::default()
        }
        fn enumerate_processes(
            &self,
        ) -> Result<Vec<pacc_platform::ProcessInfo>, pacc_platform::PlatformError> {
            Ok(vec![pacc_platform::ProcessInfo {
                name: "cheatengine-x86_64".into(),
                ..Default::default()
            }])
        }
        fn scan_memory(
            &self,
            _p: u32,
            _pat: &[u8],
        ) -> Result<Vec<pacc_platform::MemoryHit>, pacc_platform::PlatformError> {
            Err(pacc_platform::PlatformError::Unsupported("dummy"))
        }
        fn sample_input_events(
            &self,
            _w: u64,
        ) -> Result<Vec<pacc_platform::InputSample>, pacc_platform::PlatformError> {
            Err(pacc_platform::PlatformError::Unsupported("dummy"))
        }
        fn anti_debug_probe(
            &self,
        ) -> Result<pacc_platform::AntiDebugReport, pacc_platform::PlatformError> {
            Err(pacc_platform::PlatformError::Unsupported("dummy"))
        }
        fn verify_install_integrity(
            &self,
            _m: &pacc_platform::IntegrityManifest,
        ) -> Result<pacc_platform::IntegrityReport, pacc_platform::PlatformError> {
            Err(pacc_platform::PlatformError::Unsupported("dummy"))
        }
    }

    #[test]
    fn collect_is_always_178() {
        let mut c = Collector::new();
        for i in 0..10 {
            c.record_click(1000 + i * 50);
        }
        let fv = c.collect();
        assert_eq!(fv.as_slice().len(), 178);
        assert!(fv.get("feature_click_cps") > 0.0);
    }

    #[test]
    fn environment_flags_suspicious_process() {
        let fv = environment_features(&Dummy);
        assert_eq!(fv.get("feature_suspicious_process_count"), 1.0);
        assert_eq!(fv.get("feature_process_count"), 1.0);
    }
}
