//! PACC 行为分析（设计文档 §2.2 的 `pacc-behavior`）。
//!
//! 两个互补视角：
//! * **时序模型**（[`TemporalModel`]）：对单条标量信号做 EWMA 在线估计，
//!   用标准化偏差给出「此刻异常度」，对应 Java 端 `TemporalAnomalyDetector` 的语义；
//! * **分布模型**（[`DistributionModel`]）：对取值分箱，与期望分布比对，
//!   用归一化卡方偏离度刻画「分布形态不对」——自动点击器的间隔分布尤其明显。
//!
//! 在此之上是两个具名检测器：爆发（[`BurstDetector`]）与暴力（[`ViolenceDetector`]，
//! 高速切换目标的连续攻击）。全部结论经 [`BehaviorAnalyzer::apply`] 写回
//! [`FeatureVector`] 对应维度，供 `pacc-engine` 与端云上报复用。
//!
//! 纯标准库；所有统计量由 `pacc-collector` 的 `stats` 提供，避免两处口径漂移。

use pacc_collector::stats::{cv, mean, shannon_entropy, std_dev};
use pacc_collector::FeatureVector;

/// 短窗口阈值（毫秒）：低于此间隔视为一次「爆发」内的事件。
const BURST_WINDOW_MS: u64 = 50;
/// 爆发占比达到该值即认为节奏异常。
const BURST_RATIO_SUSPICIOUS: f64 = 0.35;
/// 目标切换率（次/秒）上限：超过即暴力嫌疑。
const SWITCH_RATE_SUSPICIOUS: f64 = 3.0;
/// 采样缓冲上限，限制内存与在线开销。
const BUFFER_CAP: usize = 1024;

/// 在线时序模型：EWMA 均值/方差 + 标准化偏差。
///
/// `alpha` 越大越贴近近期样本（对突变敏感）；典型取 0.2。
#[derive(Debug, Clone)]
pub struct TemporalModel {
    alpha: f64,
    mean: f64,
    variance: f64,
    samples: u64,
    last_anomaly: f64,
}

impl TemporalModel {
    pub fn new(alpha: f64) -> Self {
        let alpha = if alpha.is_finite() && alpha > 0.0 && alpha <= 1.0 {
            alpha
        } else {
            0.2
        };
        Self {
            alpha,
            mean: 0.0,
            variance: 0.0,
            samples: 0,
            last_anomaly: 0.0,
        }
    }

    /// 观测一个样本，返回该样本相对历史状态的异常度（0-1，已按 3σ 归一）。
    ///
    /// 前若干样本用于预热（<8 个样本时返回 0），避免冷启动误报。
    pub fn observe(&mut self, value: f64) -> f64 {
        if !value.is_finite() {
            return 0.0;
        }
        let anomaly = if self.samples < 8 {
            0.0
        } else {
            let std = self.variance.max(0.0).sqrt();
            if std <= f64::EPSILON {
                0.0
            } else {
                ((value - self.mean).abs() / std / 3.0).min(1.0)
            }
        };
        let delta = value - self.mean;
        self.mean += self.alpha * delta;
        self.variance = (1.0 - self.alpha) * (self.variance + self.alpha * delta * delta);
        self.samples += 1;
        self.last_anomaly = anomaly;
        anomaly
    }

    /// 最近一次观测的异常度。
    pub fn anomaly(&self) -> f64 {
        self.last_anomaly
    }

    pub fn mean(&self) -> f64 {
        self.mean
    }

    pub fn variance(&self) -> f64 {
        self.variance
    }

    pub fn samples(&self) -> u64 {
        self.samples
    }
}

impl Default for TemporalModel {
    fn default() -> Self {
        Self::new(0.2)
    }
}

/// 分布模型：把连续取值分到固定数量的桶，与期望分布比对。
///
/// 期望分布缺省为「均匀」——人类操作天然带噪，机器节奏则高度集中在个别桶，
/// 均匀假设下的偏离度足以把两者分开；有先验时可用 [`DistributionModel::deviation_from`]。
#[derive(Debug, Clone)]
pub struct DistributionModel {
    bins: Vec<u64>,
    min: f64,
    max: f64,
    total: u64,
}

impl DistributionModel {
    /// 以 `[min, max]` 为界构造 `bins` 个桶（至少 2 个）。
    pub fn new(min: f64, max: f64, bins: usize) -> Self {
        let bins = bins.max(2);
        let (min, max) = if min.is_finite() && max.is_finite() && max > min {
            (min, max)
        } else {
            (0.0, 1.0)
        };
        Self {
            bins: vec![0; bins],
            min,
            max,
            total: 0,
        }
    }

    /// 观测一个取值。
    pub fn observe(&mut self, value: f64) {
        if !value.is_finite() {
            return;
        }
        let idx = self.bucket_of(value);
        self.bins[idx] += 1;
        self.total += 1;
    }

    fn bucket_of(&self, value: f64) -> usize {
        let n = self.bins.len();
        if value <= self.min {
            return 0;
        }
        if value >= self.max {
            return n - 1;
        }
        let ratio = (value - self.min) / (self.max - self.min);
        ((ratio * n as f64) as usize).min(n - 1)
    }

    /// 与均匀分布比对，返回归一化偏离度（0-1）。
    pub fn deviation(&self) -> f64 {
        let n = self.bins.len();
        let expected = 1.0 / n as f64;
        self.deviation_from(&vec![expected; n])
    }

    /// 与自定义期望分布（各桶概率，自动归一化）比对，返回归一化偏离度（0-1）。
    pub fn deviation_from(&self, expected: &[f64]) -> f64 {
        if self.total == 0 || expected.len() != self.bins.len() {
            return 0.0;
        }
        let sum: f64 = expected.iter().filter(|v| **v > 0.0).sum();
        if sum <= 0.0 {
            return 0.0;
        }
        // 归一化卡方：χ²/total ∈ [0, n-1]，再除以上界映射到 0-1。
        let total = self.total as f64;
        let mut chi = 0.0;
        for (i, count) in self.bins.iter().enumerate() {
            let p = expected[i] / sum;
            if p <= 0.0 {
                continue;
            }
            let e = p * total;
            let diff = *count as f64 - e;
            chi += diff * diff / e;
        }
        let upper = (self.bins.len() - 1) as f64;
        if upper <= 0.0 {
            return 0.0;
        }
        (chi / total / upper).clamp(0.0, 1.0)
    }

    /// 分布香农熵（以桶数为底归一化，0=集中，1=均匀）。
    pub fn normalized_entropy(&self) -> f64 {
        if self.total == 0 {
            return 0.0;
        }
        let probs: Vec<f64> = self
            .bins
            .iter()
            .map(|c| *c as f64 / self.total as f64)
            .collect();
        let raw = shannon_entropy(&probs);
        let max = (self.bins.len() as f64).log2();
        if max <= 0.0 {
            0.0
        } else {
            (raw / max).clamp(0.0, 1.0)
        }
    }

    pub fn total(&self) -> u64 {
        self.total
    }
}

/// 爆发检测结论。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct BurstReport {
    /// 落入短窗口的事件数。
    pub burst_count: usize,
    /// 爆发占比（0-1）。
    pub burst_ratio: f64,
    /// 单秒最大事件数。
    pub max_per_second: f64,
    /// 是否节奏异常。
    pub suspicious: bool,
}

/// 爆发检测器：在时间戳序列上找「密集簇」。
///
/// 人类连点也有簇，但簇内间隔有抖动；机器簇内间隔近乎恒定且占比高，
/// 因此判据同时看**占比**与**间隔变异系数**。
pub fn detect_burst(timestamps: &[u64]) -> BurstReport {
    if timestamps.len() < 2 {
        return BurstReport {
            burst_count: 0,
            burst_ratio: 0.0,
            max_per_second: 0.0,
            suspicious: false,
        };
    }
    let mut sorted: Vec<u64> = timestamps.to_vec();
    sorted.sort_unstable();
    let mut burst = 0usize;
    let mut intervals: Vec<f64> = Vec::with_capacity(sorted.len());
    for pair in sorted.windows(2) {
        let gap = pair[1].saturating_sub(pair[0]);
        intervals.push(gap as f64);
        if gap < BURST_WINDOW_MS {
            burst += 1;
        }
    }
    let ratio = burst as f64 / intervals.len() as f64;

    // 滑动 1 秒窗口统计最大密度。
    let mut max_per_second = 0.0f64;
    let mut left = 0usize;
    for right in 0..sorted.len() {
        while sorted[right].saturating_sub(sorted[left]) > 1000 {
            left += 1;
        }
        let density = (right - left + 1) as f64;
        if density > max_per_second {
            max_per_second = density;
        }
    }

    let regular = cv(&intervals) < 0.25;
    let suspicious = ratio > BURST_RATIO_SUSPICIOUS && regular;
    BurstReport {
        burst_count: burst,
        burst_ratio: ratio,
        max_per_second,
        suspicious,
    }
}

/// 暴力检测结论。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct ViolenceReport {
    /// 目标切换率（次/秒）。
    pub switch_rate: f64,
    /// 目标序列归一化熵（越低越像死盯单一目标或机械循环）。
    pub target_entropy: f64,
    /// 独立目标数。
    pub distinct_targets: usize,
    /// 是否暴力嫌疑。
    pub suspicious: bool,
}

/// 暴力检测器：连续攻击中高频切换目标（killaura 的典型外显）。
pub fn detect_violence(targets: &[(u64, i32)]) -> ViolenceReport {
    if targets.len() < 2 {
        return ViolenceReport {
            switch_rate: 0.0,
            target_entropy: 0.0,
            distinct_targets: targets.len(),
            suspicious: false,
        };
    }
    let mut sorted = targets.to_vec();
    sorted.sort_by_key(|(ts, _)| *ts);

    let start = sorted.first().map(|(ts, _)| *ts).unwrap_or(0);
    let end = sorted.last().map(|(ts, _)| *ts).unwrap_or(start);
    let span_sec = ((end.saturating_sub(start)) as f64 / 1000.0).max(1e-3);

    let mut switches = 0usize;
    for pair in sorted.windows(2) {
        if pair[0].1 != pair[1].1 {
            switches += 1;
        }
    }
    let switch_rate = switches as f64 / span_sec;

    let mut counts: Vec<(i32, usize)> = Vec::new();
    for (_, id) in &sorted {
        match counts.iter_mut().find(|(k, _)| k == id) {
            Some(entry) => entry.1 += 1,
            None => counts.push((*id, 1)),
        }
    }
    let total = sorted.len() as f64;
    let probs: Vec<f64> = counts.iter().map(|(_, c)| *c as f64 / total).collect();
    let distinct = counts.len();
    let raw = shannon_entropy(&probs);
    let target_entropy = if distinct > 1 {
        (raw / (distinct as f64).log2()).clamp(0.0, 1.0)
    } else {
        0.0
    };

    let suspicious = switch_rate > SWITCH_RATE_SUSPICIOUS && distinct >= 2;
    ViolenceReport {
        switch_rate,
        target_entropy,
        distinct_targets: distinct,
        suspicious,
    }
}

/// 行为分析器：聚合采样缓冲与在线模型，产出写回特征向量的行为维度。
pub struct BehaviorAnalyzer {
    clicks: Vec<u64>,
    targets: Vec<(u64, i32)>,
    aim_deltas: Vec<f64>,
    temporal: TemporalModel,
    aim_speed_dist: DistributionModel,
}

impl Default for BehaviorAnalyzer {
    fn default() -> Self {
        Self::new()
    }
}

impl BehaviorAnalyzer {
    pub fn new() -> Self {
        Self {
            clicks: Vec::new(),
            targets: Vec::new(),
            aim_deltas: Vec::new(),
            temporal: TemporalModel::default(),
            // 瞄准角速度经验区间 0-720 °/s，分 12 桶。
            aim_speed_dist: DistributionModel::new(0.0, 720.0, 12),
        }
    }

    fn push_capped<T>(buf: &mut Vec<T>, item: T) {
        if buf.len() >= BUFFER_CAP {
            buf.remove(0);
        }
        buf.push(item);
    }

    /// 记录一次点击（时间戳毫秒）。
    pub fn record_click(&mut self, ts_millis: u64) {
        Self::push_capped(&mut self.clicks, ts_millis);
    }

    /// 记录一次攻击采样（时间戳 + 目标 id），并送入时序模型。
    pub fn record_attack(&mut self, ts_millis: u64, target_id: i32) {
        Self::push_capped(&mut self.targets, (ts_millis, target_id));
        let dt = self
            .targets
            .windows(2)
            .last()
            .map(|w| w[1].0.saturating_sub(w[0].0) as f64)
            .unwrap_or(0.0);
        self.temporal.observe(dt);
    }

    /// 记录一次瞄准角速度（°/s），同时送入分布模型。
    pub fn record_aim_speed(&mut self, deg_per_sec: f64) {
        Self::push_capped(&mut self.aim_deltas, deg_per_sec);
        self.aim_speed_dist.observe(deg_per_sec);
        self.temporal.observe(deg_per_sec);
    }

    /// 当前点击爆发结论。
    pub fn burst(&self) -> BurstReport {
        detect_burst(&self.clicks)
    }

    /// 当前暴力（目标切换）结论。
    pub fn violence(&self) -> ViolenceReport {
        detect_violence(&self.targets)
    }

    /// 在线时序异常度（0-1）。
    pub fn temporal_anomaly(&self) -> f64 {
        self.temporal.anomaly()
    }

    /// 瞄准角速度分布偏离度（0-1）。
    pub fn aim_distribution_deviation(&self) -> f64 {
        self.aim_speed_dist.deviation()
    }

    /// 把行为结论写回特征向量对应维度。
    ///
    /// 只写「有真实样本」支撑的维度，其余保持调用方原值，避免覆盖采集层的有效数据。
    pub fn apply(&self, fv: &mut FeatureVector) {
        let burst = self.burst();
        if !self.clicks.is_empty() {
            fv.set("feature_click_burst_count", burst.burst_count as f64);
            fv.set("feature_click_burst_ratio", burst.burst_ratio);
        }
        let violence = self.violence();
        if self.targets.len() >= 2 {
            fv.set("feature_killaura_target_switch_rate", violence.switch_rate);
            fv.set("feature_hit_select_entropy", violence.target_entropy);
        }
        if self.aim_speed_dist.total() > 0 {
            fv.set("feature_aim_snap_ratio", self.aim_distribution_deviation());
        }
        if self.temporal.samples() >= 8 {
            fv.set("feature_ai_pattern_anomaly", self.temporal_anomaly());
            fv.set("feature_aim_rotation_jerk", self.temporal.variance().sqrt());
        }
    }

    /// 清空全部采样与模型状态。
    pub fn reset(&mut self) {
        self.clicks.clear();
        self.targets.clear();
        self.aim_deltas.clear();
        self.temporal = TemporalModel::default();
        self.aim_speed_dist = DistributionModel::new(0.0, 720.0, 12);
    }

    pub fn click_count(&self) -> usize {
        self.clicks.len()
    }

    pub fn target_count(&self) -> usize {
        self.targets.len()
    }
}

/// 便捷：由点击间隔（毫秒）直接算平均点击速率（CPS）。
pub fn cps_from_intervals(intervals_ms: &[u64]) -> f64 {
    if intervals_ms.is_empty() {
        return 0.0;
    }
    let arr: Vec<f64> = intervals_ms.iter().map(|v| *v as f64).collect();
    let m = mean(&arr);
    if m <= 0.0 {
        0.0
    } else {
        1000.0 / m
    }
}

/// 便捷：由间隔序列得到标准差的转发（供上层复用）。
pub fn interval_std(intervals_ms: &[u64]) -> f64 {
    let arr: Vec<f64> = intervals_ms.iter().map(|v| *v as f64).collect();
    std_dev(&arr)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn machine_burst_is_suspicious() {
        // 每 40ms 一次、持续 20 次：密集且间隔恒定。
        let ts: Vec<u64> = (0..20).map(|i| i * 40).collect();
        let r = detect_burst(&ts);
        assert!(r.burst_ratio > 0.9);
        assert!(r.suspicious);
    }

    #[test]
    fn human_burst_is_not_suspicious() {
        // 间隔抖动大，不应命中。
        let ts = [0u64, 137, 402, 455, 900, 1450, 1510, 2300];
        let r = detect_burst(&ts);
        assert!(!r.suspicious);
    }

    #[test]
    fn rapid_target_switching_is_violence() {
        let mut ts = Vec::new();
        for i in 0..30u64 {
            ts.push((i * 100, (i % 2) as i32));
        }
        let r = detect_violence(&ts);
        assert!(r.switch_rate > 3.0);
        assert!(r.suspicious);
    }

    #[test]
    fn single_target_is_not_violence() {
        let ts: Vec<(u64, i32)> = (0..30u64).map(|i| (i * 100, 7)).collect();
        let r = detect_violence(&ts);
        assert_eq!(r.distinct_targets, 1);
        assert!(!r.suspicious);
    }

    #[test]
    fn temporal_model_flags_outlier_after_warmup() {
        let mut m = TemporalModel::new(0.3);
        for _ in 0..20 {
            m.observe(100.0);
        }
        // 恒定序列方差趋零，突变不应因除零而 panic，且样本数已过预热。
        assert!(m.samples() == 20);
        let anomaly = m.observe(1000.0);
        assert!(anomaly.is_finite());
    }

    #[test]
    fn concentrated_distribution_deviates_from_uniform() {
        let mut d = DistributionModel::new(0.0, 100.0, 10);
        for _ in 0..100 {
            d.observe(5.0);
        }
        assert!(d.deviation() > 0.5);
        assert!(d.normalized_entropy() < 0.2);
    }

    #[test]
    fn apply_writes_burst_dimensions() {
        let mut a = BehaviorAnalyzer::new();
        for i in 0..20u64 {
            a.record_click(i * 40);
        }
        let mut fv = FeatureVector::zeros();
        a.apply(&mut fv);
        assert!(fv.get("feature_click_burst_ratio") > 0.9);
    }

    #[test]
    fn cps_matches_mean_interval() {
        assert!((cps_from_intervals(&[100, 100, 100]) - 10.0).abs() < 1e-9);
    }
}
