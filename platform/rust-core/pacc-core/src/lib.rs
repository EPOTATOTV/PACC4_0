//! PACC 跨平台检测核心门面（设计文档 §4.2.1 的 `pacc-core`）。
//!
//! 把各层 crate 组装成一条可直接调用的检测管线，并对外提供两种装载方式：
//! * **Rust 侧**：`rlib` 依赖，直接用 [`PaccCore`]；
//! * **宿主侧**：`cdylib`（`libpacc_core`）经 C ABI（见文件末尾）取版本号与一次性检测报告。
//!
//! 管线顺序与 Java 端 `DetectionEngine` 对齐：
//! 采集 178 维 → 并入环境设备组只读采样 → 行为分析回写节奏/分布维度 →
//! 端侧 AI 推理（回退分不参与判定）→ 规则层 + 分值融合 → 分层处置，
//! 隐身探针独立成事件。

pub mod json;

pub use pacc_behavior as behavior;
pub use pacc_collector as collector;
pub use pacc_engine as engine;
pub use pacc_ml as ml;
pub use pacc_platform as platform;
pub use pacc_stealth as stealth;

use pacc_behavior::BehaviorAnalyzer;
use pacc_collector::FeatureVector;
use pacc_engine::{AnalysisContext, Decision, Engine, EngineVerdict};
use pacc_ml::{InferenceResult, ModelBundle};
use pacc_platform::{Capabilities, Platform};
use pacc_stealth::{StealthDetector, StealthEvent, StealthSnapshot};

use std::ffi::{c_char, CString};
use std::time::{SystemTime, UNIX_EPOCH};

/// 与版本元数据一致（Java 端 `PaccClient.APP_VERSION`）。
pub const CORE_VERSION: &str = "5.4.0";

/// 统一检测事件（与 Java 端 `DetectionEvent` 同字段，直接映射上报体）。
#[derive(Debug, Clone, PartialEq)]
pub struct DetectionEvent {
    pub event_type: String,
    pub severity: String,
    pub client_risk_score: i32,
    /// 涉事进程名（无明确目标时为 `None`，不臆造）。
    pub process_name: Option<String>,
    pub memory_region: Option<String>,
    pub signature_hit: Option<String>,
    pub os_info: String,
    /// 取证明细（扁平键值，避免嵌套结构）。
    pub detail: Vec<(String, String)>,
}

impl DetectionEvent {
    /// 编码为事件 JSON（`WssReporter.detectionEventPayload` 形态）。
    pub fn to_value(&self) -> json::Value {
        use json::Value;
        let mut entries: Vec<(String, Value)> = vec![
            ("type".to_string(), Value::string("event")),
            (
                "event_type".to_string(),
                Value::string(self.event_type.clone()),
            ),
            ("severity".to_string(), Value::string(self.severity.clone())),
            (
                "client_risk_score".to_string(),
                Value::number(self.client_risk_score as f64),
            ),
            (
                "process_name".to_string(),
                opt_string(self.process_name.as_deref()),
            ),
            (
                "memory_region".to_string(),
                opt_string(self.memory_region.as_deref()),
            ),
            (
                "signature_hit".to_string(),
                opt_string(self.signature_hit.as_deref()),
            ),
            ("os_info".to_string(), Value::string(self.os_info.clone())),
            ("client_version".to_string(), Value::string(CORE_VERSION)),
            (
                "detail".to_string(),
                Value::object(
                    self.detail
                        .iter()
                        .map(|(k, v)| (k.clone(), Value::string(v.clone()))),
                ),
            ),
        ];
        entries.shrink_to_fit();
        Value::Object(entries)
    }

    /// 编码为 JSON 文本。
    pub fn to_json(&self) -> String {
        self.to_value().encode()
    }
}

fn opt_string(value: Option<&str>) -> json::Value {
    match value {
        Some(s) => json::Value::string(s),
        None => json::Value::Null,
    }
}

/// 一次完整检测的快照结论。
#[derive(Debug, Clone)]
pub struct Snapshot {
    /// 折算后的 178 维特征向量。
    pub features: FeatureVector,
    /// 有真实数据源支撑的维度数。
    pub coverage: usize,
    /// 平台标识。
    pub platform: String,
    /// 平台能力声明。
    pub capabilities: Capabilities,
    /// 端侧 AI 结论（可能为回退）。
    pub ai: InferenceResult,
    /// 规则层 + 融合结论。
    pub verdict: EngineVerdict,
    /// 隐身探针事件（未命中为 `None`）。
    pub stealth: Option<StealthEvent>,
    /// 上报用 `os_info`（如 `linux_x64`）。
    pub os_info: String,
    /// 能力缺失说明（`None` 表示能力齐备）。
    pub capability_note: Option<&'static str>,
}

impl Snapshot {
    /// 特征覆盖度（0-1）。
    pub fn coverage_ratio(&self) -> f64 {
        self.coverage as f64 / pacc_collector::schema::DIM_COUNT as f64
    }

    /// 本轮应上报的全部事件（行为主事件恒发，隐身命中时追加）。
    pub fn events(&self) -> Vec<DetectionEvent> {
        let mut out = vec![DetectionEvent {
            event_type: self.verdict.trigger_type.to_string(),
            severity: self.verdict.severity().to_string(),
            client_risk_score: self.verdict.risk,
            process_name: None,
            memory_region: None,
            signature_hit: None,
            os_info: self.os_info.clone(),
            detail: self
                .verdict
                .findings
                .iter()
                .cloned()
                .map(|f| ("finding".to_string(), f))
                .chain(std::iter::once((
                    "decision".to_string(),
                    decision_name(self.verdict.decision).to_string(),
                )))
                .chain(std::iter::once((
                    "coverage".to_string(),
                    self.coverage.to_string(),
                )))
                .collect(),
        }];
        if let Some(s) = &self.stealth {
            out.push(DetectionEvent {
                event_type: s.event_type.to_string(),
                severity: s.severity.to_string(),
                client_risk_score: s.risk,
                process_name: None,
                memory_region: None,
                signature_hit: None,
                os_info: self.os_info.clone(),
                detail: s.detail.clone(),
            });
        }
        out
    }

    /// 完整上报载荷（HTTP POST body，含 178 维特征与全部事件）。
    pub fn to_payload_json(&self) -> String {
        use json::Value;
        let features: Vec<Value> = self
            .features
            .as_slice()
            .iter()
            .map(|v| Value::number(*v))
            .collect();
        let events: Vec<Value> = self.events().iter().map(DetectionEvent::to_value).collect();
        let caps = Value::object([
            ("process_enum", Value::Bool(self.capabilities.process_enum)),
            ("memory_scan", Value::Bool(self.capabilities.memory_scan)),
            (
                "input_sampling",
                Value::Bool(self.capabilities.input_sampling),
            ),
            ("anti_debug", Value::Bool(self.capabilities.anti_debug)),
            (
                "install_integrity",
                Value::Bool(self.capabilities.install_integrity),
            ),
        ]);
        let mut entries: Vec<(String, Value)> = vec![
            ("type".to_string(), Value::string("event")),
            ("client_version".to_string(), Value::string(CORE_VERSION)),
            ("os_info".to_string(), Value::string(self.os_info.clone())),
            ("platform".to_string(), Value::string(self.platform.clone())),
            (
                "client_risk_score".to_string(),
                Value::number(self.verdict.risk as f64),
            ),
            (
                "severity".to_string(),
                Value::string(self.verdict.severity()),
            ),
            (
                "event_type".to_string(),
                Value::string(self.verdict.trigger_type),
            ),
            (
                "decision".to_string(),
                Value::string(decision_name(self.verdict.decision)),
            ),
            ("coverage".to_string(), Value::number(self.coverage as f64)),
            ("ai_confidence".to_string(), Value::number(self.ai.score)),
            (
                "ai_source".to_string(),
                Value::string(source_name(self.ai.source)),
            ),
            ("capabilities".to_string(), caps),
            ("features".to_string(), Value::Array(features)),
            ("events".to_string(), Value::Array(events)),
        ];
        if let Some(note) = self.capability_note {
            entries.push(("capability_note".to_string(), Value::string(note)));
        }
        Value::Object(entries).encode()
    }
}

fn decision_name(decision: Decision) -> &'static str {
    match decision {
        Decision::LocalBlock => "local_block",
        Decision::ReportCloud => "report_cloud",
        Decision::Periodic => "periodic",
        Decision::ReviewQueue => "review_queue",
    }
}

fn source_name(source: pacc_ml::Source) -> &'static str {
    match source {
        pacc_ml::Source::Model => "model",
        pacc_ml::Source::Fallback => "fallback",
        pacc_ml::Source::Disabled => "disabled",
    }
}

/// 检测核心门面：持有全部子系统，串起一条检测管线。
pub struct PaccCore {
    platform: Box<dyn Platform>,
    collector: pacc_collector::Collector,
    engine: Engine,
    model: ModelBundle,
    stealth: StealthDetector,
    behavior: BehaviorAnalyzer,
    os_info: String,
}

impl Default for PaccCore {
    fn default() -> Self {
        Self::new()
    }
}

impl PaccCore {
    /// 用当前编译目标对应的平台实现构造。
    pub fn new() -> Self {
        Self::with_platform(pacc_platform::current())
    }

    /// 用指定平台实现构造（测试/宿主注入用）。
    pub fn with_platform(platform: Box<dyn Platform>) -> Self {
        let os_info = format!("{}_{}", platform.name(), std::env::consts::ARCH);
        Self {
            platform,
            collector: pacc_collector::Collector::new(),
            engine: Engine::new(),
            model: ModelBundle::empty(),
            stealth: StealthDetector::new(),
            behavior: BehaviorAnalyzer::new(),
            os_info,
        }
    }

    pub fn platform(&self) -> &dyn Platform {
        self.platform.as_ref()
    }

    pub fn collector_mut(&mut self) -> &mut pacc_collector::Collector {
        &mut self.collector
    }

    pub fn behavior_mut(&mut self) -> &mut BehaviorAnalyzer {
        &mut self.behavior
    }

    pub fn engine_mut(&mut self) -> &mut Engine {
        &mut self.engine
    }

    /// 端侧 AI 是否已装载真实模型。
    pub fn model_loaded(&self) -> bool {
        self.model.model.is_some()
    }

    /// 从文本字节装载端侧模型；签名不符或格式非法时返回原因且不改变现有模型。
    pub fn load_model(
        &mut self,
        bytes: &[u8],
        expected_signature: Option<&str>,
    ) -> Result<(), String> {
        let bundle =
            ModelBundle::load_from_bytes(bytes, expected_signature).map_err(|e| e.to_string())?;
        self.model = bundle;
        Ok(())
    }

    /// 清空采样窗口（切局/停采样）。
    pub fn reset(&mut self) {
        self.collector.reset();
        self.behavior.reset();
    }

    /// 执行一次完整检测。
    pub fn snapshot(&mut self) -> Snapshot {
        // 1) 增量采集 → 178 维
        let mut features = self.collector.collect();
        // 2) 并入环境设备组的只读采样结果
        merge_environment(
            &mut features,
            &pacc_collector::environment_features(self.platform.as_ref()),
        );
        // 3) 行为分析回写节奏/分布维度
        self.behavior.apply(&mut features);

        // 4) 端侧 AI：回退分不参与判定
        let ai = self.model.infer(&features);
        let ai_confidence = if ai.usable() { ai.score } else { 0.0 };

        // 5) 上下文：点击间隔 + 轨迹指标（全部来自已采集数据）
        let ctx = AnalysisContext {
            click_intervals: self.collector.click_intervals(),
            temporal_anomaly: self.behavior.temporal_anomaly(),
            ai_confidence,
            trajectory_rmse: features.get("feature_aim_bezier_fit_error"),
            trajectory_attraction: features.get("feature_aim_target_attraction"),
            trajectory_snap_ratio: features.get("feature_aim_snap_ratio"),
            has_trajectory: self.collector.trajectory_len() >= 2,
        };
        let verdict = self.engine.evaluate(&features, &ctx);

        // 6) 隐身探针（独立于行为判定）
        let stealth_snapshot = StealthSnapshot::from_platform(self.platform.as_ref(), now_millis());
        let stealth = self.stealth.inspect(&stealth_snapshot);

        let capabilities = self.platform.capabilities();
        Snapshot {
            coverage: features.non_placeholder_count(),
            features,
            platform: self.platform.name().to_string(),
            capability_note: self.stealth.capability_note(&capabilities),
            capabilities,
            ai,
            verdict,
            stealth,
            os_info: self.os_info.clone(),
        }
    }
}

/// 把环境设备组中**有真实取值**（非零且非中性默认）的维度并入主向量。
fn merge_environment(target: &mut FeatureVector, env: &FeatureVector) {
    for (key, _) in pacc_collector::schema::DIMS.iter() {
        let value = env.get(key);
        if value != 0.0 && value != pacc_collector::schema::neutral_default(key) {
            target.set(key, value);
        }
    }
}

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

// ---------------------------------------------------------------------------
// C ABI（cdylib 宿主：桌面壳 / 移动端 FFI）
// ---------------------------------------------------------------------------

/// 版本号静态 C 字符串（静态存储，调用方**不得**释放）。
static VERSION_CSTR: &[u8] = b"5.4.0\0";

/// 返回核心版本号（静态指针，无需释放）。
#[no_mangle]
pub extern "C" fn pacc_core_version() -> *const c_char {
    VERSION_CSTR.as_ptr() as *const c_char
}

/// 执行一次检测，返回上报载荷 JSON 的堆分配 C 字符串。
///
/// 调用方必须用 [`pacc_core_string_free`] 释放；失败返回 NULL。
#[no_mangle]
pub extern "C" fn pacc_core_run_once() -> *mut c_char {
    let mut core = PaccCore::new();
    let payload = core.snapshot().to_payload_json();
    match CString::new(payload) {
        Ok(text) => text.into_raw(),
        // 载荷含内嵌 NUL（理论上不会）时如实失败，不返回半截字符串。
        Err(_) => std::ptr::null_mut(),
    }
}

/// 释放 [`pacc_core_run_once`] 返回的字符串；传入 NULL 安全无操作。
///
/// # Safety
///
/// `ptr` 必须来自本库的 `pacc_core_run_once`，且只能释放一次。
/// 标记为 `unsafe` 是 C ABI 的既有约定：C 侧没有安全性概念，责任在调用方。
#[no_mangle]
pub unsafe extern "C" fn pacc_core_string_free(ptr: *mut c_char) {
    if ptr.is_null() {
        return;
    }
    // SAFETY: 调用方已承诺指针来自 `CString::into_raw`，且仅释放一次。
    drop(CString::from_raw(ptr));
}

#[cfg(test)]
mod tests {
    use super::*;
    use pacc_platform::PlatformError;

    /// 测试用最小平台：全部能力如实声明为不可用。
    struct NullPlatform;

    impl Platform for NullPlatform {
        fn name(&self) -> &'static str {
            "test"
        }
        fn capabilities(&self) -> Capabilities {
            Capabilities::default()
        }
        fn enumerate_processes(&self) -> Result<Vec<pacc_platform::ProcessInfo>, PlatformError> {
            Err(PlatformError::Unsupported("test"))
        }
        fn scan_memory(
            &self,
            _pid: u32,
            _pattern: &[u8],
        ) -> Result<Vec<pacc_platform::MemoryHit>, PlatformError> {
            Err(PlatformError::Unsupported("test"))
        }
        fn sample_input_events(
            &self,
            _window_ms: u64,
        ) -> Result<Vec<pacc_platform::InputSample>, PlatformError> {
            Err(PlatformError::Unsupported("test"))
        }
        fn anti_debug_probe(&self) -> Result<pacc_platform::AntiDebugReport, PlatformError> {
            Err(PlatformError::Unsupported("test"))
        }
        fn verify_install_integrity(
            &self,
            _manifest: &pacc_platform::IntegrityManifest,
        ) -> Result<pacc_platform::IntegrityReport, PlatformError> {
            Err(PlatformError::Unsupported("test"))
        }
    }

    #[test]
    fn snapshot_produces_178_features_and_event() {
        let mut core = PaccCore::with_platform(Box::new(NullPlatform));
        for i in 0..20u64 {
            core.collector_mut().record_click(i * 40);
            core.behavior_mut().record_click(i * 40);
        }
        let snap = core.snapshot();
        assert_eq!(snap.features.as_slice().len(), 178);
        assert_eq!(snap.os_info, "test_x86_64");
        let events = snap.events();
        assert!(!events.is_empty());
        assert_eq!(events[0].os_info, "test_x86_64");
        // 能力缺失必须如实上报，不能被静默忽略。
        assert!(snap.capability_note.is_some());
    }

    #[test]
    fn payload_json_is_valid_and_has_features() {
        let mut core = PaccCore::with_platform(Box::new(NullPlatform));
        let snap = core.snapshot();
        let text = snap.to_payload_json();
        let parsed = json::parse(&text).expect("载荷必须是合法 JSON");
        assert_eq!(
            parsed
                .get("features")
                .and_then(json::Value::as_array)
                .map(|a| a.len()),
            Some(178)
        );
        assert_eq!(
            parsed.get("client_version").and_then(json::Value::as_str),
            Some("5.4.0")
        );
    }

    #[test]
    fn model_load_rejects_bad_signature() {
        let mut core = PaccCore::with_platform(Box::new(NullPlatform));
        let model = "pacc-model 1\nkind=linear\nversion=5.4.0\nbias=0\nw=0:1.0\nsha256=abc\n";
        assert!(core.load_model(model.as_bytes(), Some("abc")).is_ok());
        assert!(core.model_loaded());
        assert!(core.load_model(model.as_bytes(), Some("zzz")).is_err());
        // 装载失败不得破坏已装载的模型。
        assert!(core.model_loaded());
    }

    #[test]
    fn c_abi_version_and_free_round_trip() {
        let ptr = pacc_core_version();
        assert!(!ptr.is_null());
        let text = unsafe { std::ffi::CStr::from_ptr(ptr) };
        assert_eq!(text.to_str().unwrap(), "5.4.0");
        // 释放路径必须能安全处理 NULL 与真实指针（指针来自本库，故此处 unsafe 有据）。
        unsafe {
            pacc_core_string_free(std::ptr::null_mut());
        }
        // `run_once` 里的环境采集走真实 /proc，非 Linux 上无此数据源，只在本平台回归。
        #[cfg(target_os = "linux")]
        {
            let payload = pacc_core_run_once();
            assert!(!payload.is_null());
            unsafe {
                pacc_core_string_free(payload);
            }
        }
    }
}
