//! PACC 检测引擎（设计文档 §2.3 的 `pacc-engine`）。
//!
//! 实现与 Java 端 `BruteForceDetector` + `LayeredDecision` 同口径的两段式判定：
//! 1. **L0 规则层**：明确的硬阈值与行为规则，命中即累计权重；
//! 2. **分值融合**：规则权重 + 点击间隔分布 + 轨迹分析 + 时序自编码 + 端侧 AI
//!    加权求和，裁到 0-100 得到风险分；
//! 3. **分层判定**：依 `LayeredDecision` 阈值映射为端侧处置 / 上报云端 / 周期上报。
//!
//! AI 置信度由调用方（`pacc-core`）从 `pacc-ml` 传入；**回退分不参与判定**。

use pacc_collector::stats::{cv, mean, std_dev};
use pacc_collector::FeatureVector;

// ---- L0 硬阈值（与 Java 端数值一致，作为高置信直判） ----
const CPS_HARD: f64 = 14.0;
const ANGLE_HARD: f64 = 45.0;
const SPEED_HARD: f64 = 1.5;
const FLY_HARD: f64 = 1.2;
const REACH_HARD: f64 = 4.0;

// ---- 各层信号权重 ----
const W_CPS_HARD: i32 = 55;
const W_ANGLE_HARD: i32 = 55;
const W_SPEED_HARD: i32 = 50;
const W_FLY_HARD: i32 = 65;
const W_REACH_HARD: i32 = 50;
const W_CLICK_HIGHLY_LIKELY: i32 = 45;
const W_CLICK_SUSPECTED: i32 = 20;
const W_ATTRACTION: i32 = 40;
const W_SNAP: i32 = 20;
/// 时序异常「显著」阈值。
const TEMPORAL_SIGNIFICANT: f64 = 0.5;
/// 时序异常「强烈」阈值。
const TEMPORAL_STRONG: f64 = 1.0;
const W_TEMPORAL: i32 = 25;
const W_TEMPORAL_STRONG: i32 = 15;
const W_AI_MAX: i32 = 50;
/// 目标吸引判定阈值（余弦相似度）。
const ATTRACTION_THRESHOLD: f64 = 0.8;
/// 目标吸引同时要求拟合误差偏高，避免把人类直甩判成自瞄。
const ATTRACTION_MIN_RMSE: f64 = 5.0;
/// 瞬移占比阈值。
const SNAP_RATIO_THRESHOLD: f64 = 0.5;

/// L1 端侧 AI 直接红屏阈值（`LayeredDecision.LOCAL_REDSCREEN_THRESHOLD`）。
pub const LOCAL_REDSCREEN_THRESHOLD: f64 = 0.85;
/// 上报云端精判的下限（`LayeredDecision.CLOUD_REPORT_THRESHOLD`）。
pub const CLOUD_REPORT_THRESHOLD: f64 = 0.5;

/// 端云分层处置结论。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Decision {
    /// 高置信：端侧直接处置（红屏/阻断）。
    LocalBlock,
    /// 中置信：上报云端精判。
    ReportCloud,
    /// 低置信：仅周期性上报。
    Periodic,
    /// 零日可疑：进入人工复核队列。
    ReviewQueue,
}

/// 依「规则命中 + AI 置信度」映射处置层级。
pub fn decide(rule_hit: bool, ai_confidence: f64) -> Decision {
    if rule_hit || ai_confidence > LOCAL_REDSCREEN_THRESHOLD {
        return Decision::LocalBlock;
    }
    if ai_confidence >= CLOUD_REPORT_THRESHOLD {
        return Decision::ReportCloud;
    }
    Decision::Periodic
}

/// 单条规则命中。
#[derive(Debug, Clone, PartialEq)]
pub struct RuleHit {
    /// 作弊类型 code（如 `autoclicker` / `killaura`）。
    pub code: &'static str,
    /// 风险权重。
    pub weight: i32,
    /// 命中描述（取证用）。
    pub detail: String,
}

/// L0 规则。
pub trait Rule: Send + Sync {
    fn key(&self) -> &'static str;
    fn evaluate(&self, fv: &FeatureVector) -> Option<RuleHit>;
}

/// 阈值比较方向。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Compare {
    Above,
    Below,
}

/// 单特征阈值规则。
pub struct ThresholdRule {
    pub key: &'static str,
    pub feature: &'static str,
    pub threshold: f64,
    pub weight: i32,
    pub code: &'static str,
    pub compare: Compare,
}

impl Rule for ThresholdRule {
    fn key(&self) -> &'static str {
        self.key
    }

    fn evaluate(&self, fv: &FeatureVector) -> Option<RuleHit> {
        let v = fv.get(self.feature);
        let triggered = match self.compare {
            Compare::Above => v > self.threshold,
            Compare::Below => v < self.threshold,
        };
        if !triggered {
            return None;
        }
        Some(RuleHit {
            code: self.code,
            weight: self.weight,
            detail: format!("{}={:.3} (阈值 {:.3})", self.feature, v, self.threshold),
        })
    }
}

/// 规则库：默认装载与 Java 端一致的硬阈值规则。
pub struct RuleRegistry {
    rules: Vec<Box<dyn Rule>>,
}

impl Default for RuleRegistry {
    fn default() -> Self {
        Self::with_defaults()
    }
}

impl RuleRegistry {
    /// 空规则库。
    pub fn empty() -> Self {
        Self { rules: Vec::new() }
    }

    /// 装载文档 §2.3 的默认硬阈值规则。
    pub fn with_defaults() -> Self {
        let rules: Vec<Box<dyn Rule>> = vec![
            Box::new(ThresholdRule {
                key: "cps_hard",
                feature: "feature_click_cps",
                threshold: CPS_HARD,
                weight: W_CPS_HARD,
                code: "autoclicker",
                compare: Compare::Above,
            }),
            Box::new(ThresholdRule {
                key: "angle_hard",
                feature: "feature_killaura_angle_speed",
                threshold: ANGLE_HARD,
                weight: W_ANGLE_HARD,
                code: "killaura",
                compare: Compare::Above,
            }),
            Box::new(ThresholdRule {
                key: "speed_hard",
                feature: "feature_speed_ratio",
                threshold: SPEED_HARD,
                weight: W_SPEED_HARD,
                code: "speed",
                compare: Compare::Above,
            }),
            Box::new(ThresholdRule {
                key: "fly_hard",
                feature: "feature_fly_vertical_speed",
                threshold: FLY_HARD,
                weight: W_FLY_HARD,
                code: "fly",
                compare: Compare::Above,
            }),
            Box::new(ThresholdRule {
                key: "reach_hard",
                feature: "feature_reach_distance",
                threshold: REACH_HARD,
                weight: W_REACH_HARD,
                code: "reach",
                compare: Compare::Above,
            }),
        ];
        Self { rules }
    }

    pub fn push(&mut self, rule: Box<dyn Rule>) {
        self.rules.push(rule);
    }

    /// 依次评估，返回全部命中。
    pub fn evaluate(&self, fv: &FeatureVector) -> Vec<RuleHit> {
        self.rules.iter().filter_map(|r| r.evaluate(fv)).collect()
    }
}

/// 点击间隔分布分析结论。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClickVerdict {
    /// 样本不足，不下结论。
    Insufficient,
    /// 拟人。
    Human,
    /// 可疑。
    Suspected,
    /// 高度疑似自动点击。
    HighlyLikely,
}

/// 点击间隔分析（对数正态 KS 检验的轻量近似：变异系数 + 均值 + 爆发占比）。
pub fn analyze_clicks(intervals: &[u64]) -> ClickVerdict {
    if intervals.len() < 8 {
        return ClickVerdict::Insufficient;
    }
    let arr: Vec<f64> = intervals.iter().map(|v| *v as f64).collect();
    let m = mean(&arr);
    let c = cv(&arr);
    let burst = intervals.iter().filter(|i| **i < 30).count() as f64 / intervals.len() as f64;
    // 机器点击：间隔极规律（低变异）且高频，或爆发占比很高。
    if m < 120.0 && c < 0.15 && burst > 0.3 {
        ClickVerdict::HighlyLikely
    } else if m < 200.0 && c < 0.3 && burst > 0.15 {
        ClickVerdict::Suspected
    } else {
        ClickVerdict::Human
    }
}

/// 行为分析上下文：把各分析器结论打包，供规则与融合复用。
#[derive(Debug, Clone, Default)]
pub struct AnalysisContext {
    pub click_intervals: Vec<u64>,
    /// 时序自编码异常分（无模型为 0）。
    pub temporal_anomaly: f64,
    /// 端侧 AI 置信度（无模型回退为 0）。
    pub ai_confidence: f64,
    /// 轨迹三点拟合 RMSE（无轨迹为 0）。
    pub trajectory_rmse: f64,
    /// 轨迹目标吸引（余弦）。
    pub trajectory_attraction: f64,
    /// 瞬移占比。
    pub trajectory_snap_ratio: f64,
    /// 是否有可用轨迹。
    pub has_trajectory: bool,
}

/// 融合判定结论。
#[derive(Debug, Clone, PartialEq)]
pub struct EngineVerdict {
    pub risk: i32,
    pub rule_hit: bool,
    pub ai_confidence: f64,
    pub trigger_type: &'static str,
    pub decision: Decision,
    pub findings: Vec<String>,
}

impl Default for EngineVerdict {
    fn default() -> Self {
        Self {
            risk: 0,
            rule_hit: false,
            ai_confidence: 0.0,
            trigger_type: "behavior",
            decision: Decision::Periodic,
            findings: Vec::new(),
        }
    }
}

impl EngineVerdict {
    pub fn severity(&self) -> &'static str {
        if self.risk >= 70 {
            "high"
        } else if self.risk >= 45 {
            "medium"
        } else {
            "low"
        }
    }
}

/// 检测引擎：规则层 + 分值融合。
#[derive(Default)]
pub struct Engine {
    registry: RuleRegistry,
}

impl Engine {
    pub fn new() -> Self {
        Self {
            registry: RuleRegistry::with_defaults(),
        }
    }

    pub fn with_registry(registry: RuleRegistry) -> Self {
        Self { registry }
    }

    pub fn registry_mut(&mut self) -> &mut RuleRegistry {
        &mut self.registry
    }

    /// 融合判定：返回完整结论（与 Java 端 `BruteForceDetector.evaluate` 等价）。
    pub fn evaluate(&self, fv: &FeatureVector, ctx: &AnalysisContext) -> EngineVerdict {
        let mut risk = 0i32;
        let mut findings: Vec<String> = Vec::new();
        let mut trigger: Option<&'static str> = None;

        // ---- 1. L0 规则层 ----
        let hits = self.registry.evaluate(fv);
        for hit in &hits {
            risk += hit.weight;
            findings.push(format!("{}: {}", hit.code, hit.detail));
            if trigger.is_none() {
                trigger = Some(hit.code);
            }
        }

        // ---- 2. 点击间隔分布 ----
        let mut click_suspicious = false;
        match analyze_clicks(&ctx.click_intervals) {
            ClickVerdict::HighlyLikely => {
                risk += W_CLICK_HIGHLY_LIKELY;
                click_suspicious = true;
                if trigger.is_none() {
                    trigger = Some("autoclicker");
                }
                findings.push("点击间隔高度疑似自动点击".to_string());
            }
            ClickVerdict::Suspected => {
                risk += W_CLICK_SUSPECTED;
                click_suspicious = true;
                if trigger.is_none() {
                    trigger = Some("autoclicker");
                }
                findings.push("点击间隔可疑".to_string());
            }
            ClickVerdict::Insufficient | ClickVerdict::Human => {}
        }

        // ---- 3. 鼠标轨迹 ----
        let mut trajectory_suspicious = false;
        if ctx.has_trajectory {
            if ctx.trajectory_attraction > ATTRACTION_THRESHOLD
                && ctx.trajectory_rmse > ATTRACTION_MIN_RMSE
            {
                risk += W_ATTRACTION;
                trajectory_suspicious = true;
                if trigger.is_none() {
                    trigger = Some("killaura");
                }
                findings.push("轨迹目标吸引显著".to_string());
            }
            if ctx.trajectory_snap_ratio > SNAP_RATIO_THRESHOLD {
                risk += W_SNAP;
                trajectory_suspicious = true;
                findings.push("轨迹瞬移占比高".to_string());
            }
        }

        // ---- 4. 时序自编码 ----
        let mut temporal_hit = false;
        if ctx.temporal_anomaly > TEMPORAL_SIGNIFICANT {
            risk += W_TEMPORAL;
            temporal_hit = true;
            findings.push(format!("时序重构误差 {:.3}", ctx.temporal_anomaly));
        }
        if ctx.temporal_anomaly > TEMPORAL_STRONG {
            risk += W_TEMPORAL_STRONG;
        }

        // ---- 5. 端侧 AI（回退分已由调用方置 0） ----
        if ctx.ai_confidence > 0.0 {
            risk += (ctx.ai_confidence * W_AI_MAX as f64).round() as i32;
        }

        risk = risk.clamp(0, 100);
        let rule_hit =
            !hits.is_empty() || click_suspicious || trajectory_suspicious || temporal_hit;
        let decision = decide(rule_hit, ctx.ai_confidence);

        EngineVerdict {
            risk,
            rule_hit,
            ai_confidence: ctx.ai_confidence,
            trigger_type: trigger.unwrap_or("behavior"),
            decision,
            findings,
        }
    }
}

/// 便捷：直接由特征向量推断行为类型（无上下文）。
pub fn quick_scan(fv: &FeatureVector) -> Option<RuleHit> {
    RuleRegistry::with_defaults()
        .evaluate(fv)
        .into_iter()
        .next()
}

/// 标准差的便捷转发（供上层复用，避免重复依赖 collector 内部模块）。
pub fn interval_std(intervals: &[u64]) -> f64 {
    let arr: Vec<f64> = intervals.iter().map(|v| *v as f64).collect();
    std_dev(&arr)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hard_thresholds_trigger() {
        let mut fv = FeatureVector::zeros();
        fv.set("feature_click_cps", 20.0);
        let e = Engine::new();
        let v = e.evaluate(&fv, &AnalysisContext::default());
        assert!(v.rule_hit);
        assert_eq!(v.trigger_type, "autoclicker");
        assert_eq!(v.decision, Decision::LocalBlock);
        assert!(v.risk >= W_CPS_HARD);
    }

    #[test]
    fn clean_features_stay_periodic() {
        let fv = FeatureVector::neutral();
        let e = Engine::new();
        let v = e.evaluate(&fv, &AnalysisContext::default());
        assert_eq!(v.risk, 0);
        assert_eq!(v.decision, Decision::Periodic);
    }

    #[test]
    fn ai_only_maps_to_cloud() {
        let fv = FeatureVector::zeros();
        let ctx = AnalysisContext {
            ai_confidence: 0.6,
            ..AnalysisContext::default()
        };
        let v = Engine::new().evaluate(&fv, &ctx);
        assert_eq!(v.decision, Decision::ReportCloud);
    }
}
