//! PACC 隐身外挂检测（设计文档 §4 的 `pacc-stealth`）。
//!
//! 覆盖硬件层（DMA）/ 内存层（反射式注入）/ 环境层（调试通道、虚拟化、沙箱）
//! 的端侧预判，语义与 Java 端 `detection.StealthDetector` 对齐。
//! 网络层与行为层不在本 crate，由 `pacc-engine` / 云端承担。
//!
//! 三态字段（[`Option`]）表示「未知」：探测失败或平台不提供时**不做推断**，
//! 既不报警也不计入证据——把未知当成阴性是最危险的误判。

use pacc_platform::{AntiDebugReport, Capabilities, Platform};

// ---- 风险权重（与 Java 端数值一致） ----
/// 命中可疑 DMA 设备（Xilinx/Altera 等 FPGA 板卡）。
const W_DMA: i32 = 70;
/// 可疑 DMA 叠加 IOMMU 关闭：直读物理内存的完整条件。
const W_DMA_NO_IOMMU: i32 = 30;
/// 注入痕迹基础分（未知 agent / attach 文件）。
const W_INJECT: i32 = 60;
/// 每条额外注入痕迹加分上限。
const W_INJECT_EXTRA: i32 = 20;
/// 检出调试通道：玩家机上出现调试通道即中风险。
const W_DEBUGGER: i32 = 50;
/// 虚拟化 + 多沙箱特征（分析环境嫌疑）。
const W_SANDBOX: i32 = 45;
/// 仅虚拟化：只体现在特征里，不产生事件。
const W_VM_ONLY: i32 = 20;
/// 事件上报下限：低于该分只进特征向量，避免给云端刷稳态信号。
pub const REPORT_FLOOR: i32 = 45;
/// 触发多沙箱判定所需的沙箱特征命中数。
const SANDBOX_INDICATOR_MIN: i32 = 3;

/// 一次隐身探针的取证结果。
///
/// 与 Java 端 `StealthSnapshot` 同构：`Option<bool>` 表示三态，列表字段为原始证据
/// （采集侧须限制条数，不要把整份线程表/注册表塞进来）。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct StealthSnapshot {
    /// PCIe 是否真的枚举成功（false 时 DMA 结论恒为未知）。
    pub pcie_enumerated: bool,
    /// 命中的可疑 DMA 设备（`VID:DID(厂商)`）。
    pub suspicious_pcie: Vec<String>,
    /// IOMMU 是否被关闭；`None` 表示查不到。
    pub iommu_disabled: Option<bool>,
    /// 非 PACC 的 agent 注入项（启动参数或环境变量）。
    pub unknown_agents: Vec<String>,
    /// Attach API 留下的痕迹文件。
    pub attach_artifacts: Vec<String>,
    /// 非白名单线程数。
    pub unknown_threads: i32,
    /// 其中最多 5 个线程名（证据）。
    pub unknown_thread_names: Vec<String>,
    /// 是否检出调试通道。
    pub debugger_present: bool,
    /// 调试通道证据。
    pub debug_channels: Vec<String>,
    /// 是否虚拟化环境；`None` 表示未知。
    pub vm: Option<bool>,
    /// Hypervisor 位；`None` 表示未知。
    pub hypervisor: Option<bool>,
    /// 虚拟化证据。
    pub vm_evidence: Vec<String>,
    /// 沙箱特征命中项数。
    pub sandbox_indicators: i32,
    /// 沙箱证据。
    pub sandbox_evidence: Vec<String>,
    /// 探测时刻（毫秒）。
    pub probed_at_millis: u64,
}

impl StealthSnapshot {
    /// DMA 设备命中（需要枚举成功）。
    pub fn dma_present(&self) -> bool {
        self.pcie_enumerated && !self.suspicious_pcie.is_empty()
    }

    /// 是否存在明确注入痕迹（未知 agent 或 attach 文件）。
    pub fn injected(&self) -> bool {
        !self.unknown_agents.is_empty() || !self.attach_artifacts.is_empty()
    }

    /// 虚拟化或 Hypervisor 任一为真。
    pub fn vm_like(&self) -> bool {
        self.vm == Some(true) || self.hypervisor == Some(true)
    }

    /// 由平台反调试探针构造快照。非 Linux 平台（`Unsupported`）只留未知，不臆断。
    pub fn from_platform(platform: &dyn Platform, probed_at_millis: u64) -> Self {
        let mut snapshot = Self {
            probed_at_millis,
            ..Self::default()
        };
        if let Ok(report) = platform.anti_debug_probe() {
            Self::apply_anti_debug(&mut snapshot, &report);
        }
        snapshot
    }

    /// 把反调试结论合入快照：命中 `TracerPid`/证据即记为调试通道。
    pub fn apply_anti_debug(&mut self, report: &AntiDebugReport) {
        if report.debugger_present {
            self.debugger_present = true;
        }
        for finding in &report.findings {
            if !self.debug_channels.contains(finding) {
                self.debug_channels.push(finding.clone());
            }
        }
        if let Some(tracer) = report.tracer_pid {
            if tracer != 0 {
                self.debugger_present = true;
                let note = format!("tracer_pid={tracer}");
                if !self.debug_channels.contains(&note) {
                    self.debug_channels.push(note);
                }
            }
        }
    }
}

/// 隐身检测事件。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StealthEvent {
    /// 作弊类型 code（如 `dma_cheat` / `reflective_dll`）。
    pub event_type: &'static str,
    /// 严重级（`high` / `medium`）。
    pub severity: &'static str,
    /// 风险分（0-100）。
    pub risk: i32,
    /// 取证结论明细（键值对，直接进事件 `detail`）。
    pub detail: Vec<(String, String)>,
}

/// 隐身外挂检测器。
pub struct StealthDetector;

impl Default for StealthDetector {
    fn default() -> Self {
        Self::new()
    }
}

impl StealthDetector {
    pub fn new() -> Self {
        Self
    }

    /// 判定入口：基于真实探针快照。低于 [`REPORT_FLOOR`] 或无类型时不产生事件。
    ///
    /// 规则：可疑 DMA 命中即高危；注入痕迹按注入上报；调试通道单列中风险；
    /// 虚拟化只在同时命中多个沙箱特征时才上报（单开虚拟机是合法场景）。
    pub fn inspect(&self, s: &StealthSnapshot) -> Option<StealthEvent> {
        let mut risk = 0i32;
        let mut event_type: Option<&'static str> = None;

        if s.dma_present() {
            risk += W_DMA;
            event_type = Some("dma_cheat");
            if s.iommu_disabled == Some(true) {
                risk += W_DMA_NO_IOMMU;
            }
        }
        if s.injected() {
            let artifacts = (s.unknown_agents.len() + s.attach_artifacts.len()) as i32;
            let score = W_INJECT + (artifacts * 10).min(W_INJECT_EXTRA);
            risk = risk.max(score);
            if event_type.is_none() {
                event_type = Some("reflective_dll");
            }
        }
        if s.debugger_present {
            risk = risk.max(W_DEBUGGER);
            if event_type.is_none() {
                event_type = Some("anti_debug");
            }
        }
        if s.vm_like() && s.sandbox_indicators >= SANDBOX_INDICATOR_MIN {
            risk = risk.max(W_SANDBOX);
            if event_type.is_none() {
                event_type = Some("sandbox");
            }
        } else if s.vm_like() && risk == 0 {
            risk = W_VM_ONLY;
            if event_type.is_none() {
                event_type = Some("virtualization");
            }
        }

        let event_type = event_type?;
        if risk < REPORT_FLOOR {
            return None;
        }
        let risk = risk.min(100);
        let severity = if risk >= 70 { "high" } else { "medium" };
        Some(StealthEvent {
            event_type,
            severity,
            risk,
            detail: self.detail(s),
        })
    }

    /// 事件明细：只带取证结论，不含原始注册表 / 线程表全量内容。
    pub fn detail(&self, s: &StealthSnapshot) -> Vec<(String, String)> {
        let mut out: Vec<(String, String)> = Vec::new();
        out.push(("pcie_dma_present".to_string(), s.dma_present().to_string()));
        push_joined(&mut out, "suspicious_pcie", &s.suspicious_pcie);
        if let Some(disabled) = s.iommu_disabled {
            out.push(("iommu_disabled".to_string(), disabled.to_string()));
        }
        push_joined(&mut out, "unknown_agents", &s.unknown_agents);
        push_joined(&mut out, "attach_artifacts", &s.attach_artifacts);
        out.push(("unknown_threads".to_string(), s.unknown_threads.to_string()));
        push_joined(&mut out, "unknown_thread_names", &s.unknown_thread_names);
        if s.debugger_present {
            push_joined(&mut out, "debug_channels", &s.debug_channels);
        }
        if let Some(vm) = s.vm {
            out.push(("vm".to_string(), vm.to_string()));
        }
        if let Some(hypervisor) = s.hypervisor {
            out.push(("hypervisor".to_string(), hypervisor.to_string()));
        }
        push_joined(&mut out, "vm_evidence", &s.vm_evidence);
        out.push((
            "sandbox_indicators".to_string(),
            s.sandbox_indicators.to_string(),
        ));
        push_joined(&mut out, "sandbox_evidence", &s.sandbox_evidence);
        out
    }

    /// 由平台能力声明判断本机能否做隐身取证；能力缺失时返回原因，供上层如实记录。
    pub fn capability_note(&self, caps: &Capabilities) -> Option<&'static str> {
        if !caps.anti_debug {
            return Some("平台不提供反调试探测，隐身结论为未知（非阴性）");
        }
        None
    }
}

/// 列表按逗号拼接成单个标量：事件明细为扁平键值，避免引入嵌套结构。
fn push_joined(out: &mut Vec<(String, String)>, key: &str, values: &[String]) {
    if values.is_empty() {
        return;
    }
    out.push((key.to_string(), values.join(",")));
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dma_without_iommu_is_high() {
        let s = StealthSnapshot {
            pcie_enumerated: true,
            suspicious_pcie: vec!["10EE:7024(Xilinx)".to_string()],
            iommu_disabled: Some(true),
            ..StealthSnapshot::default()
        };
        let e = StealthDetector::new().inspect(&s).expect("应命中 DMA");
        assert_eq!(e.event_type, "dma_cheat");
        assert_eq!(e.severity, "high");
        assert_eq!(e.risk, 100);
    }

    #[test]
    fn vm_only_stays_below_floor() {
        let s = StealthSnapshot {
            vm: Some(true),
            ..StealthSnapshot::default()
        };
        assert!(StealthDetector::new().inspect(&s).is_none());
    }

    #[test]
    fn vm_with_sandbox_reports_medium() {
        let s = StealthSnapshot {
            hypervisor: Some(true),
            sandbox_indicators: 4,
            ..StealthSnapshot::default()
        };
        let e = StealthDetector::new().inspect(&s).expect("沙箱应命中");
        assert_eq!(e.event_type, "sandbox");
        assert_eq!(e.severity, "medium");
    }

    #[test]
    fn unknown_never_reports() {
        let s = StealthSnapshot::default();
        assert!(StealthDetector::new().inspect(&s).is_none());
    }

    #[test]
    fn anti_debug_merges_into_snapshot() {
        let report = AntiDebugReport {
            debugger_present: false,
            tracer_pid: Some(4242),
            findings: vec!["yama_ptrace_scope=0".to_string()],
        };
        let mut s = StealthSnapshot::default();
        s.apply_anti_debug(&report);
        assert!(s.debugger_present);
        assert!(s.debug_channels.iter().any(|c| c == "tracer_pid=4242"));
        let e = StealthDetector::new().inspect(&s).expect("调试通道应命中");
        assert_eq!(e.event_type, "anti_debug");
    }
}
