//! PACC 端侧 AI 推理（设计文档 §2.1.4 的 `pacc-ml`）。
//!
//! 只用标准库实现「装载模型 → 推理」，不引入任何张量框架：
//! 端侧模型落地为两种轻量形态——线性（logistic）判别器与时序自编码器
//! （逐维标准化重构误差，对应 Java 端 `TemporalAnomalyDetector` 的语义）。
//!
//! 推理结论的 [`Source`] 表达数据来源：无模型时返回
//! [`Source::Fallback`]，其分值只是启发式的「非零维度占比」，**不是置信度**，
//! 上层（`pacc-engine`）必须像 Java 端一样忽略它，避免把回退分当成真实判定。
//!
//! 模型文本格式（便于离线打包与审计）：
//! ```text
//! pacc-model 1
//! kind=linear
//! version=5.4.0
//! bias=-1.20
//! w=0:0.51,3:1.20,52:0.80
//! sha256=<hex>            # 可选，装载方按字节比对，密级校验由发布链路负责
//! ```

use pacc_collector::FeatureVector;
use std::fmt;

/// 推理分值来源。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Source {
    /// 真实模型推理。
    Model,
    /// 无可用模型时的启发式回退（分值不参与判定）。
    Fallback,
    /// 显式关闭端侧 AI。
    Disabled,
}

/// 一次推理结论。
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct InferenceResult {
    pub score: f64,
    pub source: Source,
}

impl InferenceResult {
    fn fallback(features: &FeatureVector) -> Self {
        let total = pacc_collector::schema::DIM_COUNT as f64;
        Self {
            score: features.non_placeholder_count() as f64 / total,
            source: Source::Fallback,
        }
    }

    fn disabled() -> Self {
        Self {
            score: 0.0,
            source: Source::Disabled,
        }
    }

    /// 该结论是否可用于判定（回退/关闭均不可用）。
    pub fn usable(&self) -> bool {
        self.source == Source::Model
    }
}

/// 模型格式错误。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ModelFormatError(pub String);

impl fmt::Display for ModelFormatError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "模型格式错误: {}", self.0)
    }
}

impl std::error::Error for ModelFormatError {}

/// 线性（logistic）判别器。
#[derive(Debug, Clone, Default)]
pub struct LinearModel {
    pub version: String,
    /// 参与推理的维度下标。
    pub indices: Vec<usize>,
    /// 与 `indices` 一一对应的权重。
    pub weights: Vec<f64>,
    pub bias: f64,
}

impl LinearModel {
    /// 推理：`sigmoid(bias + Σ w_i·x_i)` → [0,1]。
    pub fn infer(&self, features: &FeatureVector) -> f64 {
        let mut z = self.bias;
        for (idx, w) in self.indices.iter().zip(self.weights.iter()) {
            z += w * features.get_idx(*idx);
        }
        sigmoid(z)
    }
}

/// 时序自编码器：按训练集的均值/标准差做逐维标准化，取均方重构误差。
#[derive(Debug, Clone, Default)]
pub struct Autoencoder {
    pub version: String,
    pub mean: Vec<f64>,
    pub std: Vec<f64>,
}

impl Autoencoder {
    /// 异常分：标准化后偏离均值的均方（越大越异常）。
    pub fn reconstruct_error(&self, features: &FeatureVector) -> f64 {
        if self.mean.is_empty() {
            return 0.0;
        }
        let mut se = 0.0;
        let mut n = 0usize;
        for (i, mean) in self.mean.iter().enumerate() {
            let std = self.std.get(i).copied().unwrap_or(1.0).max(1e-6);
            let x = features.get_idx(i);
            se += ((x - mean) / std).powi(2);
            n += 1;
        }
        if n == 0 {
            0.0
        } else {
            se / n as f64
        }
    }
}

/// 端侧模型。
#[derive(Debug, Clone)]
pub enum Model {
    Linear(LinearModel),
    Autoencoder(Autoencoder),
}

impl Model {
    pub fn version(&self) -> &str {
        match self {
            Model::Linear(m) => &m.version,
            Model::Autoencoder(m) => &m.version,
        }
    }

    /// 统一推理入口，返回 [0,1] 区间的判定分。
    pub fn infer(&self, features: &FeatureVector) -> f64 {
        match self {
            Model::Linear(m) => m.infer(features),
            // 自编码误差理论上无上界，映射到 [0,1] 便于与线性模型同口径比较。
            Model::Autoencoder(m) => 1.0 - (-m.reconstruct_error(features)).exp(),
        }
    }

    /// 时序异常分（仅自编码器有意义）。
    pub fn anomaly_score(&self, features: &FeatureVector) -> f64 {
        match self {
            Model::Autoencoder(m) => m.reconstruct_error(features),
            Model::Linear(_) => 0.0,
        }
    }
}

/// 模型包：模型 + 版本 + 可选签名。
#[derive(Debug, Clone, Default)]
pub struct ModelBundle {
    pub model: Option<Model>,
    pub signature: Option<String>,
}

impl ModelBundle {
    /// 无模型包（推理一律回退）。
    pub fn empty() -> Self {
        Self::default()
    }

    /// 从文本字节装载模型。`expected_signature` 提供时按字节比对签名。
    pub fn load_from_bytes(
        bytes: &[u8],
        expected_signature: Option<&str>,
    ) -> Result<Self, ModelFormatError> {
        let text = std::str::from_utf8(bytes)
            .map_err(|e| ModelFormatError(format!("非 UTF-8 文本: {e}")))?;
        let mut lines = text.lines().filter(|l| {
            let t = l.trim();
            !t.is_empty() && !t.starts_with('#')
        });
        let header = lines
            .next()
            .ok_or_else(|| ModelFormatError("空模型".into()))?;
        if !header.starts_with("pacc-model") {
            return Err(ModelFormatError(format!("缺少 pacc-model 头: {header}")));
        }

        let mut kind = String::new();
        let mut version = String::new();
        let mut signature = None;
        let mut bias = 0.0;
        let mut weights_raw = String::new();
        let mut mean_raw = String::new();
        let mut std_raw = String::new();

        for line in lines {
            let (key, value) = line
                .split_once('=')
                .ok_or_else(|| ModelFormatError(format!("非法行: {line}")))?;
            match key.trim() {
                "kind" => kind = value.trim().to_string(),
                "version" => version = value.trim().to_string(),
                "bias" => {
                    bias = value
                        .trim()
                        .parse()
                        .map_err(|_| ModelFormatError(format!("bias 非法: {value}")))?
                }
                "w" => weights_raw = value.trim().to_string(),
                "mean" => mean_raw = value.trim().to_string(),
                "std" => std_raw = value.trim().to_string(),
                "sha256" => signature = Some(value.trim().to_string()),
                _ => {}
            }
        }

        if let Some(expected) = expected_signature {
            match &signature {
                Some(actual) if actual == expected => {}
                _ => return Err(ModelFormatError("模型签名不匹配".into())),
            }
        }

        let model = match kind.as_str() {
            "linear" => {
                let (indices, weights) = parse_weight_pairs(&weights_raw)?;
                Model::Linear(LinearModel {
                    version,
                    indices,
                    weights,
                    bias,
                })
            }
            "autoencoder" => Model::Autoencoder(Autoencoder {
                version,
                mean: parse_floats(&mean_raw)?,
                std: parse_floats(&std_raw)?,
            }),
            other => return Err(ModelFormatError(format!("未知 kind: {other}"))),
        };

        Ok(Self {
            model: Some(model),
            signature,
        })
    }

    /// 推理入口：无模型返回回退结论。
    pub fn infer(&self, features: &FeatureVector) -> InferenceResult {
        match &self.model {
            Some(m) => InferenceResult {
                score: m.infer(features),
                source: Source::Model,
            },
            None => InferenceResult::fallback(features),
        }
    }

    /// 关闭端侧 AI 时的结论。
    pub fn disabled() -> InferenceResult {
        InferenceResult::disabled()
    }
}

fn parse_weight_pairs(raw: &str) -> Result<(Vec<usize>, Vec<f64>), ModelFormatError> {
    let mut indices = Vec::new();
    let mut weights = Vec::new();
    for pair in raw.split(',').filter(|s| !s.trim().is_empty()) {
        let (idx, w) = pair
            .split_once(':')
            .ok_or_else(|| ModelFormatError(format!("权重对非法: {pair}")))?;
        let idx: usize = idx
            .trim()
            .parse()
            .map_err(|_| ModelFormatError(format!("下标非法: {idx}")))?;
        let w: f64 = w
            .trim()
            .parse()
            .map_err(|_| ModelFormatError(format!("权重非法: {w}")))?;
        if idx >= pacc_collector::schema::DIM_COUNT {
            return Err(ModelFormatError(format!("下标越界: {idx}")));
        }
        indices.push(idx);
        weights.push(w);
    }
    Ok((indices, weights))
}

fn parse_floats(raw: &str) -> Result<Vec<f64>, ModelFormatError> {
    raw.split(',')
        .filter(|s| !s.trim().is_empty())
        .map(|s| {
            s.trim()
                .parse::<f64>()
                .map_err(|_| ModelFormatError(format!("数值非法: {s}")))
        })
        .collect()
}

fn sigmoid(z: f64) -> f64 {
    1.0 / (1.0 + (-z).exp())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn linear_model_infers() {
        let text = "pacc-model 1\nkind=linear\nversion=t\nbias=-1.0\nw=0:0.5,1:0.5\n";
        let bundle = ModelBundle::load_from_bytes(text.as_bytes(), None).unwrap();
        let mut fv = FeatureVector::zeros();
        fv.set_idx(0, 4.0);
        fv.set_idx(1, 4.0);
        let r = bundle.infer(&fv);
        assert_eq!(r.source, Source::Model);
        assert!(r.score > 0.5 && r.score <= 1.0);
    }

    #[test]
    fn empty_bundle_falls_back() {
        let bundle = ModelBundle::empty();
        let fv = FeatureVector::zeros();
        let r = bundle.infer(&fv);
        assert_eq!(r.source, Source::Fallback);
        assert!(!r.usable());
    }

    #[test]
    fn signature_mismatch_rejected() {
        let text = "pacc-model 1\nkind=linear\nversion=t\nsha256=abc\nw=0:1\n";
        assert!(ModelBundle::load_from_bytes(text.as_bytes(), Some("def")).is_err());
        assert!(ModelBundle::load_from_bytes(text.as_bytes(), Some("abc")).is_ok());
    }

    #[test]
    fn autoencoder_error_grows_with_deviation() {
        let ae = Autoencoder {
            version: "t".into(),
            mean: vec![0.0, 0.0],
            std: vec![1.0, 1.0],
        };
        let mut fv = FeatureVector::zeros();
        let near = ae.reconstruct_error(&fv);
        fv.set_idx(0, 3.0);
        assert!(ae.reconstruct_error(&fv) > near);
    }
}
