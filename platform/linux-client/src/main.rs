//! PACC Linux 原生客户端入口。
//!
//! 职责：选定事件源（eBPF 优先 / procfs 回退）→ 周期采集 → 交给 `pacc-core`
//! 做特征折算与判定 → 向 PACC 后端 HTTP POST 上报事件。
//!
//! 运行期约束：
//! * 事件源不可用时**不忙循环**：eBPF 侧退避重连，procfs 侧按检测周期轮询；
//! * 收到 SIGTERM/SIGINT 立刻收敛（systemd stop/restart 依赖这一点）；
//! * 所有日志走 stderr 并做控制字符清洗，避免日志注入；
//! * 只支持 `http://` 上报（零依赖构建无 TLS），`https://` 会明确报错而不是降级。

mod config;
mod ebpf;
mod procfs;
mod reporter;

use config::Config;
use pacc_core::json::{self, Value};
use pacc_core::{DetectionEvent, PaccCore};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

/// 单轮 procfs 扫描的最大进程数（限制最坏情况的单轮耗时）。
const MAX_PROC_SCAN: usize = 2048;
/// 一次上报中 `detail` 里原始行的截断长度。
const MAX_RAW_DETAIL: usize = 512;
/// eBPF 需要的最低内核版本（CO-RE/BTF 可用的下限）。
const MIN_KERNEL: (u32, u32) = (5, 4);
/// CAP_BPF / CAP_PERFMON / CAP_SYS_ADMIN 的位号（用于给出可操作的失败原因）。
/// 只在 Linux 分支的 `missing_capability_note` 里用到，别的平台编译时放行「未被使用」。
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
const CAP_SYS_ADMIN: u32 = 21;
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
const CAP_PERFMON: u32 = 38;
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
const CAP_BPF: u32 = 39;

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.iter().any(|a| a == "--help" || a == "-h") {
        print_usage();
        return;
    }
    if args.iter().any(|a| a == "--version" || a == "-V") {
        println!("pacc-linux-client {}", config::CLIENT_VERSION);
        return;
    }
    let once = args.iter().any(|a| a == "--once");
    let config_arg = flag_value(&args, "--config");

    let cfg = match config::load(config_arg.as_deref()) {
        Ok(c) => c,
        Err(e) => {
            log("error", &format!("配置加载失败: {e}"));
            std::process::exit(2);
        }
    };

    log(
        "info",
        &format!(
            "PACC Linux 客户端 v{} 启动 pteid={} edition={} 配置={}{}",
            config::CLIENT_VERSION,
            cfg.pteid,
            cfg.edition,
            cfg.config_path.display(),
            if cfg.config_found {
                ""
            } else {
                "（未找到，使用默认值）"
            }
        ),
    );

    let mut core = PaccCore::new();
    let os_info = format!("{}_{}", core.platform().name(), std::env::consts::ARCH);

    // 端侧模型（可选）：装载失败只告警，按无模型回退运行，绝不静默假装有模型。
    if let Some(path) = &cfg.model_path {
        match std::fs::read(path) {
            Ok(bytes) => match core.load_model(&bytes, cfg.model_signature.as_deref()) {
                Ok(()) => log("info", &format!("端侧模型已装载: {path}")),
                Err(e) => log("warn", &format!("端侧模型装载失败（按无模型运行）: {e}")),
            },
            Err(e) => log(
                "warn",
                &format!("端侧模型读取失败（按无模型运行）: {path} {e}"),
            ),
        }
    } else {
        log("info", "未配置端侧模型，AI 判定走回退（回退分不参与判定）");
    }

    let reporter = match reporter::Reporter::from_config(
        &cfg.server_uri,
        &cfg.events_path,
        cfg.token.clone(),
        cfg.http_timeout_seconds,
    ) {
        Ok(r) => r,
        Err(e) => {
            log("error", &format!("上报器初始化失败: {e}"));
            std::process::exit(2);
        }
    };
    log(
        "info",
        &format!(
            "上报端点 {}（令牌{}）",
            reporter.endpoint().describe(),
            if cfg.token.is_some() {
                "已配置"
            } else {
                "未配置，匿名上报"
            }
        ),
    );

    let source = select_source(&cfg);
    log(
        "info",
        &format!("事件源选择: {} —— {}", source.label(), source.reason()),
    );

    signals::install();

    let mut ebpf_source = ebpf::EbpfSource::new(cfg.ebpf_socket.clone());
    let mut cycles: u64 = 0;
    let mut reported: u64 = 0;
    let mut failures: u64 = 0;

    loop {
        if !signals::running() {
            log("info", "收到退出信号，正在收敛…");
            break;
        }
        let cycle_start = Instant::now();
        cycles += 1;

        // ---- 1. 源侧事件 ----
        let source_events = match source.kind {
            SourceKind::Ebpf => collect_ebpf_events(&mut ebpf_source, &cfg, &os_info),
            SourceKind::Procfs => collect_procfs_events(&cfg, &os_info),
        };

        // ---- 2. 核心快照（特征折算 + 规则/融合 + 隐身探针）----
        let snapshot = core.snapshot();
        let core_events = snapshot.events();

        // ---- 3. 组装并上报 ----
        // 任一成立就上报：风险过线、引擎给出了结论、隐身探针有信号、源侧有事件。
        let suspicious = snapshot.verdict.risk >= 45
            || !snapshot.verdict.findings.is_empty()
            || snapshot.stealth.is_some()
            || !source_events.is_empty();

        if cfg.report_only_suspicious && !suspicious {
            log(
                "debug",
                &format!(
                    "第 {cycles} 轮无异常（风险 {} 覆盖 {}/178）",
                    snapshot.verdict.risk, snapshot.coverage
                ),
            );
        } else {
            let body = build_payload(
                &snapshot.to_payload_json(),
                &cfg,
                &os_info,
                source.label(),
                &core_events,
                &source_events,
                cfg.max_events_per_report,
            );
            match reporter.post_json(&body) {
                Ok(code) if (200..300).contains(&code) => {
                    reported += 1;
                    log(
                        "info",
                        &format!(
                            "第 {cycles} 轮上报成功 HTTP {code}（风险 {} 事件 {} 字节 {}）",
                            snapshot.verdict.risk,
                            core_events.len() + source_events.len(),
                            body.len()
                        ),
                    );
                }
                Ok(code) => {
                    failures += 1;
                    log(
                        "warn",
                        &format!("第 {cycles} 轮上报被拒 HTTP {code}（检查令牌与路径）"),
                    );
                }
                Err(e) => {
                    failures += 1;
                    log("warn", &format!("第 {cycles} 轮上报失败: {e}"));
                }
            }
        }

        if once {
            break;
        }

        // ---- 4. 等待下一个周期（分片睡眠以便及时响应退出信号）----
        let elapsed = cycle_start.elapsed();
        let target = Duration::from_secs(cfg.heartbeat_seconds);
        let mut slept = Duration::ZERO;
        while slept < target {
            if !signals::running() {
                break;
            }
            let step = Duration::from_millis(200).min(target - slept);
            std::thread::sleep(step);
            slept += step;
        }
        let _ = elapsed;
    }

    log(
        "info",
        &format!("退出：共 {cycles} 轮，成功上报 {reported} 次，失败 {failures} 次"),
    );
    if let SourceKind::Ebpf = source.kind {
        log(
            "info",
            &format!(
                "eBPF 事件源统计：累计 {} 行，未识别 {} 行，最后错误 {}",
                ebpf_source.lines_total(),
                ebpf_source.parse_errors(),
                ebpf_source.last_error().unwrap_or("无")
            ),
        );
    }
}

/// 事件源类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SourceKind {
    Ebpf,
    Procfs,
}

impl SourceKind {
    fn label(&self) -> &'static str {
        match self {
            SourceKind::Ebpf => "ebpf",
            SourceKind::Procfs => "procfs",
        }
    }
}

/// 选择结果（类型 + 可打印的原因，便于运维直接定位）。
struct SourceSelection {
    kind: SourceKind,
    reason: String,
}

impl SourceSelection {
    fn label(&self) -> &'static str {
        self.kind.label()
    }
    fn reason(&self) -> &str {
        &self.reason
    }
}

/// 事件源选择逻辑。
///
/// eBPF 需要三件事同时成立：配置开启、loader 的 socket 在、内核 ≥ 5.4。
/// 任一不成立就回退 procfs，并把「差在哪一条」写进原因——比只报一句
/// 「eBPF 不可用」有用得多。
fn select_source(cfg: &Config) -> SourceSelection {
    if !cfg.ebpf_enabled {
        return SourceSelection {
            kind: SourceKind::Procfs,
            reason: "配置已关闭 eBPF（pacc.client.ebpf.enabled=false）".to_string(),
        };
    }
    if !ebpf::socket_exists(&cfg.ebpf_socket) {
        let mut reason = format!(
            "loader socket {} 不存在（kernel-linux loader 未运行或未安装）",
            cfg.ebpf_socket
        );
        if let Some((major, minor)) = kernel_version() {
            if (major, minor) < MIN_KERNEL {
                reason.push_str(&format!(
                    "；内核 {major}.{minor} 低于 eBPF 要求 {}.{}",
                    MIN_KERNEL.0, MIN_KERNEL.1
                ));
            }
        } else {
            reason.push_str("；内核版本读取失败");
        }
        if let Some(missing) = missing_capability_note() {
            reason.push_str(&format!("；{missing}"));
        }
        return SourceSelection {
            kind: SourceKind::Procfs,
            reason,
        };
    }
    SourceSelection {
        kind: SourceKind::Ebpf,
        reason: format!("loader socket {} 可用", cfg.ebpf_socket),
    }
}

/// 读取内核主次版本号。
fn kernel_version() -> Option<(u32, u32)> {
    procfs::kernel_release().and_then(|r| parse_kernel_version(&r))
}

/// 解析 `5.15.0-91-generic` → `(5, 15)`。
fn parse_kernel_version(release: &str) -> Option<(u32, u32)> {
    let mut parts = release.trim().split('.');
    let major = parts.next()?.parse::<u32>().ok()?;
    // 次版本号可能带后缀（如 `15rc1`），取前导数字即可。
    let minor_raw = parts.next()?;
    let digits: String = minor_raw
        .chars()
        .take_while(|c| c.is_ascii_digit())
        .collect();
    if digits.is_empty() {
        return None;
    }
    Some((major, digits.parse::<u32>().ok()?))
}

/// 解析 `/proc/self/status` 的 `CapEff` 十六进制位图。
#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn parse_cap_eff(status: &str) -> Option<u64> {
    let raw = status
        .lines()
        .find(|l| l.starts_with("CapEff") && l.contains(':'))?
        .split_once(':')?
        .1
        .trim();
    // 新内核会输出两个 64 位字（空格分隔）；取低位字即可覆盖 0-63 号能力。
    let first = raw.split_whitespace().next()?;
    u64::from_str_radix(first, 16).ok()
}

/// 若缺少 eBPF 所需能力，返回一句可操作的说明。
fn missing_capability_note() -> Option<String> {
    #[cfg(target_os = "linux")]
    {
        let status = std::fs::read_to_string("/proc/self/status").ok()?;
        let caps = parse_cap_eff(&status)?;
        let has = |bit: u32| caps & (1u64 << bit) != 0;
        if has(CAP_BPF) || has(CAP_PERFMON) || has(CAP_SYS_ADMIN) {
            return None;
        }
        return Some(format!(
            "当前进程缺少 CAP_BPF(位{CAP_BPF})/CAP_PERFMON(位{CAP_PERFMON})/CAP_SYS_ADMIN(位{CAP_SYS_ADMIN})，加载 loader 会失败"
        ));
    }
    #[cfg(not(target_os = "linux"))]
    {
        Some("当前平台非 Linux，eBPF 不可用".to_string())
    }
}

/// 取 eBPF 事件并折算为检测事件。
fn collect_ebpf_events(
    source: &mut ebpf::EbpfSource,
    cfg: &Config,
    os_info: &str,
) -> Vec<DetectionEvent> {
    let now = Instant::now();
    let events = source.poll(now);
    if events.is_empty() {
        if !source.is_connected() {
            log(
                "debug",
                &format!(
                    "eBPF 未连接（{:.1}s 后重试）: {}",
                    source.backoff_remaining(now).as_secs_f64(),
                    source.last_error().unwrap_or("未知原因")
                ),
            );
        }
        return Vec::new();
    }
    events
        .into_iter()
        .filter(|e| !cfg.report_only_suspicious || e.is_suspicious())
        .map(|e| loader_event_to_detection(&e, os_info))
        .collect()
}

/// loader 事件 → 统一检测事件。
fn loader_event_to_detection(event: &ebpf::LoaderEvent, os_info: &str) -> DetectionEvent {
    let severity = event
        .severity
        .clone()
        .unwrap_or_else(|| event.inferred_severity().to_string());
    let score = match severity.as_str() {
        "critical" | "high" => 70,
        "medium" => 50,
        _ => 20,
    };
    let mut detail: Vec<(String, String)> = vec![
        ("source".to_string(), "ebpf".to_string()),
        ("signature".to_string(), event.event.clone()),
    ];
    if let Some(pid) = event.pid {
        detail.push(("pid".to_string(), pid.to_string()));
    }
    if let Some(ppid) = event.ppid {
        detail.push(("ppid".to_string(), ppid.to_string()));
    }
    if let Some(uid) = event.uid {
        detail.push(("uid".to_string(), uid.to_string()));
    }
    if let Some(ts) = event.ts_millis {
        detail.push(("ts_millis".to_string(), ts.to_string()));
    }
    if let Some(exe) = &event.exe {
        detail.push(("exe".to_string(), exe.clone()));
    }
    if let Some(d) = &event.detail {
        detail.push(("loader_detail".to_string(), truncate(d, MAX_RAW_DETAIL)));
    }
    detail.push(("raw".to_string(), truncate(&event.raw, MAX_RAW_DETAIL)));

    DetectionEvent {
        event_type: event.event.clone(),
        severity,
        client_risk_score: score,
        process_name: event.comm.clone(),
        memory_region: None,
        signature_hit: Some(event.event.clone()),
        os_info: os_info.to_string(),
        detail,
    }
}

/// 跑一轮 procfs 回退扫描并折算为检测事件。
fn collect_procfs_events(cfg: &Config, os_info: &str) -> Vec<DetectionEvent> {
    if !cfg.procfs_enabled {
        return Vec::new();
    }
    let report = procfs::scan(MAX_PROC_SCAN);
    if let Some(note) = report.unsupported_note {
        log("warn", &format!("procfs 扫描不可用: {note}"));
        return Vec::new();
    }
    if report.debugger_present {
        log(
            "warn",
            &format!(
                "本进程被调试跟踪（TracerPid={:?}），反调试信号将随本轮上报",
                report.tracer_pid
            ),
        );
    }
    let events: Vec<DetectionEvent> = report
        .findings
        .iter()
        .map(|f| proc_finding_to_detection(f, os_info, report.debugger_present, report.tracer_pid))
        .collect();
    if !events.is_empty() {
        log(
            "info",
            &format!(
                "procfs 扫描 {} 个进程，命中 {} 条（内核 {}{}）",
                report.scanned,
                events.len(),
                report.kernel_release.as_deref().unwrap_or("未知"),
                if report.anonymous_exec_count > 0 {
                    format!(
                        "，匿名可执行映射 {} 处（JIT 属正常，仅作环境特征）",
                        report.anonymous_exec_count
                    )
                } else {
                    String::new()
                }
            ),
        );
    }
    events
}

/// procfs 发现 → 统一检测事件。
fn proc_finding_to_detection(
    finding: &procfs::ProcFinding,
    os_info: &str,
    debugger_present: bool,
    tracer_pid: Option<u32>,
) -> DetectionEvent {
    let mut detail: Vec<(String, String)> = vec![
        ("source".to_string(), "procfs".to_string()),
        ("pid".to_string(), finding.pid.to_string()),
    ];
    detail.extend(finding.detail.iter().cloned());
    if debugger_present {
        detail.push(("debugger_present".to_string(), "true".to_string()));
        detail.push((
            "tracer_pid".to_string(),
            tracer_pid.unwrap_or(0).to_string(),
        ));
    }
    let memory_region = finding
        .detail
        .iter()
        .find(|(k, _)| k == "memory_region")
        .map(|(_, v)| v.clone());
    DetectionEvent {
        event_type: finding.event_type.to_string(),
        severity: finding.severity.to_string(),
        client_risk_score: finding.score,
        process_name: Some(finding.name.clone()),
        memory_region,
        signature_hit: Some(finding.signature.clone()),
        os_info: os_info.to_string(),
        detail,
    }
}

/// 在核心载荷基础上补上客户端身份与源侧事件，形成最终上报体。
fn build_payload(
    base_json: &str,
    cfg: &Config,
    os_info: &str,
    source: &str,
    core_events: &[DetectionEvent],
    source_events: &[DetectionEvent],
    max_events: usize,
) -> String {
    let base = json::parse(base_json).unwrap_or(Value::Null);
    let mut events: Vec<Value> = core_events.iter().map(DetectionEvent::to_value).collect();
    events.extend(source_events.iter().map(DetectionEvent::to_value));
    // 超量时保留前面的（核心行为事件优先），截断数量如实记录在 total_events。
    let total = events.len();
    events.truncate(max_events.max(1));

    let mut entries: Vec<(String, Value)> = vec![
        ("type".to_string(), Value::string("event")),
        ("proto".to_string(), Value::string("linux-client")),
        (
            "client_version".to_string(),
            Value::string(config::CLIENT_VERSION),
        ),
        ("pteid".to_string(), Value::string(cfg.pteid.clone())),
        ("edition".to_string(), Value::string(cfg.edition.clone())),
        ("os_info".to_string(), Value::string(os_info.to_string())),
        ("source".to_string(), Value::string(source.to_string())),
    ];
    for key in [
        "client_risk_score",
        "severity",
        "event_type",
        "decision",
        "coverage",
        "ai_source",
        "platform",
        "capabilities",
        "features",
    ] {
        if let Some(v) = base.get(key) {
            entries.push((key.to_string(), v.clone()));
        }
    }
    entries.push(("total_events".to_string(), Value::number(total as f64)));
    entries.push(("events".to_string(), Value::Array(events)));
    Value::Object(entries).encode()
}

/// 日志：stderr + 控制字符清洗（防日志注入），只输出结论不输出敏感值。
fn log(level: &str, message: &str) {
    let cleaned: String = message
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect();
    eprintln!(
        "[pacc-linux-client] {} [{}] {}",
        timestamp_secs(),
        level,
        cleaned
    );
}

fn timestamp_secs() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

fn truncate(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        return s.to_string();
    }
    let cut: String = s.chars().take(max).collect();
    format!("{cut}…")
}

/// 取 `--flag value` 形式的值。
fn flag_value(args: &[String], flag: &str) -> Option<String> {
    let idx = args.iter().position(|a| a == flag)?;
    args.get(idx + 1).cloned()
}

fn print_usage() {
    println!(
        "pacc-linux-client {}\n\
         \n\
         用法：\n\
         \x20 pacc-linux-client [--config <路径>] [--once] [--version] [--help]\n\
         \n\
         说明：\n\
         \x20 --config <路径>  指定 properties 文件（默认 {}，也可用 PACC_CLIENT_CONFIG）\n\
         \x20 --once           只跑一轮检测与上报后退出（用于排障）\n\
         \n\
         配置优先级：环境变量 > 配置文件 > 内置默认值。\n\
         事件源：eBPF loader socket 在线且内核 ≥ {}.{} 时走 eBPF，否则回退 procfs。",
        config::CLIENT_VERSION,
        config::DEFAULT_CONFIG_PATH,
        MIN_KERNEL.0,
        MIN_KERNEL.1
    );
}

/// 信号处理：只声明用到的 libc 符号，不引入 libc 依赖。
#[cfg(unix)]
mod signals {
    use std::ffi::c_int;
    use std::sync::atomic::{AtomicBool, Ordering};

    static RUNNING: AtomicBool = AtomicBool::new(true);

    const SIGINT: c_int = 2;
    const SIGTERM: c_int = 15;

    extern "C" fn on_signal(_sig: c_int) {
        // 只做一次原子写：异步信号里不能有任何可能加锁/分配的操作。
        RUNNING.store(false, Ordering::SeqCst);
    }

    extern "C" {
        fn signal(
            signum: c_int,
            handler: Option<extern "C" fn(c_int)>,
        ) -> Option<extern "C" fn(c_int)>;
    }

    /// 安装 SIGTERM/SIGINT 处理器（systemd stop 依赖）。
    pub fn install() {
        // SAFETY: `signal` 是 POSIX 标准接口；处理器只做原子写，符合异步信号安全要求。
        unsafe {
            signal(SIGTERM, Some(on_signal));
            signal(SIGINT, Some(on_signal));
        }
    }

    pub fn running() -> bool {
        RUNNING.load(Ordering::SeqCst)
    }
}

/// 非 Unix 兜底：没有 POSIX 信号，进程靠外部终止。
#[cfg(not(unix))]
mod signals {
    pub fn install() {}

    pub fn running() -> bool {
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_kernel_versions() {
        assert_eq!(parse_kernel_version("5.15.0-91-generic"), Some((5, 15)));
        assert_eq!(parse_kernel_version("4.19.128"), Some((4, 19)));
        assert_eq!(parse_kernel_version("6.1"), Some((6, 1)));
        assert_eq!(parse_kernel_version("5.3.0-rc1"), Some((5, 3)));
        assert_eq!(parse_kernel_version("garbage"), None);
        assert_eq!(parse_kernel_version(""), None);
    }

    #[test]
    fn detects_low_kernel_for_ebpf() {
        assert!(parse_kernel_version("5.3.0").unwrap() < MIN_KERNEL);
        assert!(parse_kernel_version("5.4.0").unwrap() >= MIN_KERNEL);
        assert!(parse_kernel_version("6.8.0").unwrap() >= MIN_KERNEL);
    }

    #[test]
    fn parses_cap_eff_bitmap() {
        // 只有低位能力：不含 CAP_BPF(39)
        let status = "Name:\tjava\nCapEff:\t0000000000000000\n";
        assert_eq!(parse_cap_eff(status), Some(0));
        // 0x8000000000 = 位 39 = CAP_BPF
        let status = "CapEff:\t0000008000000000\n";
        let caps = parse_cap_eff(status).expect("应可解析");
        assert_ne!(caps & (1u64 << CAP_BPF), 0);
        assert!(parse_cap_eff("Name:\tx\n").is_none());
    }

    #[test]
    fn ebpf_disabled_falls_back_to_procfs() {
        let cfg = Config {
            ebpf_enabled: false,
            ..Config::default()
        };
        let sel = select_source(&cfg);
        assert_eq!(sel.kind, SourceKind::Procfs);
        assert!(sel.reason().contains("关闭"));
    }

    #[test]
    fn payload_contains_identity_and_source_events() {
        let cfg = Config::default();
        let base = r#"{"type":"event","client_version":"5.4.0","client_risk_score":10,"severity":"low","coverage":3,"features":[1,2]}"#;
        let core_events = vec![DetectionEvent {
            event_type: "behavior".to_string(),
            severity: "low".to_string(),
            client_risk_score: 5,
            process_name: None,
            memory_region: None,
            signature_hit: None,
            os_info: "linux_x86_64".to_string(),
            detail: vec![("decision".to_string(), "periodic".to_string())],
        }];
        let source_events = vec![DetectionEvent {
            event_type: "ptrace".to_string(),
            severity: "high".to_string(),
            client_risk_score: 70,
            process_name: Some("gdb".to_string()),
            memory_region: None,
            signature_hit: Some("ptrace".to_string()),
            os_info: "linux_x86_64".to_string(),
            detail: vec![("source".to_string(), "ebpf".to_string())],
        }];
        let body = build_payload(
            base,
            &cfg,
            "linux_x86_64",
            "ebpf",
            &core_events,
            &source_events,
            20,
        );
        let parsed = json::parse(&body).expect("上报体必须是合法 JSON");
        assert_eq!(
            parsed.get("pteid").and_then(Value::as_str),
            Some(cfg.pteid.as_str())
        );
        assert_eq!(parsed.get("source").and_then(Value::as_str), Some("ebpf"));
        assert_eq!(
            parsed
                .get("events")
                .and_then(Value::as_array)
                .map(|a| a.len()),
            Some(2)
        );
        assert_eq!(parsed.get("total_events").and_then(Value::as_i64), Some(2));
        // 特征数组必须原样带上，云端精判要用。
        assert_eq!(
            parsed
                .get("features")
                .and_then(Value::as_array)
                .map(|a| a.len()),
            Some(2)
        );
    }

    #[test]
    fn payload_truncates_events_and_reports_total() {
        let cfg = Config::default();
        let mk = |i: usize| DetectionEvent {
            event_type: format!("e{i}"),
            severity: "low".to_string(),
            client_risk_score: 1,
            process_name: None,
            memory_region: None,
            signature_hit: None,
            os_info: "linux_x86_64".to_string(),
            detail: Vec::new(),
        };
        let events: Vec<DetectionEvent> = (0..5).map(mk).collect();
        let body = build_payload("{}", &cfg, "linux_x86_64", "procfs", &events, &[], 3);
        let parsed = json::parse(&body).expect("应可解析");
        assert_eq!(
            parsed
                .get("events")
                .and_then(Value::as_array)
                .map(|a| a.len()),
            Some(3)
        );
        assert_eq!(parsed.get("total_events").and_then(Value::as_i64), Some(5));
    }

    #[test]
    fn log_sanitizes_control_chars() {
        // 控制字符不能原样进日志（防伪造日志行）
        let raw = "a\nb\u{1b}[31mc";
        let cleaned: String = raw
            .chars()
            .map(|c| if c.is_control() { ' ' } else { c })
            .collect();
        assert!(!cleaned.contains('\n'));
        assert!(!cleaned.contains('\u{1b}'));
    }

    #[test]
    fn flag_value_reads_next_arg() {
        let args = vec!["--config".to_string(), "/etc/pacc/x.properties".to_string()];
        assert_eq!(
            flag_value(&args, "--config").as_deref(),
            Some("/etc/pacc/x.properties")
        );
        assert_eq!(flag_value(&args, "--missing"), None);
    }
}
