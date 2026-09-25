//! 纯 Rust procfs 回退扫描器。
//!
//! 用途：当 eBPF 不可用时（内核 < 5.4、进程无 `CAP_BPF`/`CAP_PERFMON`、
//! loader 未运行）仍然给出**可解释**的端侧结论——枚举 `/proc`，读 `comm`、
//! `cmdline`、`exe`、`status`、`maps`，命中三类信号：
//!
//! 1. **已知作弊/调试工具名**：进程名或命令行里出现特征词；
//! 2. **可执行内存异常**：`rwx` 映射（W^X 破坏）、`/memfd:` 可执行映射、
//!    `(deleted)` 可执行映射——三者都是「内存里跑落地不了的代码」的典型形态；
//! 3. **调试通道**：`/proc/self/status` 的 `TracerPid` 非 0。
//!
//! 刻意**不**把「匿名 `r-xp` 无路径映射」当作告警：JVM 的 JIT 代码缓存、
//! 动态链接器的 `[vdso]` 都是这个样子，误报会把正常玩家全打成可疑。
//! 该计数只作环境特征，不出事件。
//!
//! 特征表与判定函数在非 Linux 平台不会被调用（没有 procfs），
//! 此处按平台放行「未被使用」，而不是把它们删掉——真到 Linux 上还要用。

#![cfg_attr(not(target_os = "linux"), allow(dead_code))]

/// 进程快照条目（procfs 可见的最小字段集）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcEntry {
    pub pid: u32,
    pub ppid: u32,
    pub comm: String,
    pub cmdline: String,
    pub exe: Option<String>,
    pub uid: u32,
    /// 内核线程（无用户态映像，`exe` 取不到且 `cmdline` 为空）。
    pub kernel_thread: bool,
}

/// 命中特征。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProcSignature {
    /// 入库用特征名（上报体 `signature_hit`）。
    pub signature: &'static str,
    /// 事件类型（上报体 `event_type`）。
    pub event_type: &'static str,
    pub severity: &'static str,
    /// 风险权重（0-100）。
    pub score: i32,
    pub reason: &'static str,
}

/// 一条扫描发现。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcFinding {
    pub pid: u32,
    pub name: String,
    pub signature: String,
    pub event_type: &'static str,
    pub severity: &'static str,
    pub score: i32,
    pub detail: Vec<(String, String)>,
}

/// 一次 procfs 扫描报告。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProcScanReport {
    /// 实际扫描的进程数。
    pub scanned: usize,
    /// 内核版本（`/proc/sys/kernel/osrelease`）。
    pub kernel_release: Option<String>,
    /// 命中特征词的进程数。
    pub suspicious_processes: usize,
    /// 无可读路径的可执行映射数（仅作环境特征，不出事件）。
    pub anonymous_exec_count: usize,
    /// 本进程是否被跟踪。
    pub debugger_present: bool,
    pub tracer_pid: Option<u32>,
    pub findings: Vec<ProcFinding>,
    /// 非 Linux 平台的原因说明。
    pub unsupported_note: Option<&'static str>,
}

/// 已知作弊工具特征词（命中即高危）。
const CHEAT_SIGNATURES: &[(&str, &str)] = &[
    ("xray", "xray"),
    ("wurst", "wurst"),
    ("meteor", "meteor_client"),
    ("impact", "impact"),
    ("aristois", "aristois"),
    ("baritone", "baritone"),
    ("killaura", "killaura"),
    ("aimbot", "aimbot"),
    ("autoclicker", "autoclicker"),
    ("auto-clicker", "autoclicker"),
    ("cheatengine", "cheat_engine"),
    ("scanmem", "cheat_engine"),
    ("gameconqueror", "cheat_engine"),
    ("frida", "frida"),
    ("dobby", "dobby_hook"),
    ("substrate", "substrate_hook"),
    ("xposed", "xposed"),
    ("linux-inject", "process_injector"),
    ("ld-inject", "process_injector"),
];

/// 调试/逆向工具特征词（命中为中危：玩家机上不该有，但开发机上合法）。
const DEBUG_SIGNATURES: &[(&str, &str)] = &[
    ("gdb", "gdb"),
    ("lldb", "lldb"),
    ("ptrace", "ptrace_tool"),
    ("strace", "strace"),
    ("ltrace", "ltrace"),
    ("radare2", "radare2"),
    ("ghidra", "ghidra"),
    ("ida64", "ida"),
    ("ida32", "ida"),
];

/// 名称 → 特征（不区分大小写；`None` 表示未命中）。
pub fn signature_for(name: &str) -> Option<ProcSignature> {
    let lower = name.to_ascii_lowercase();
    for (needle, signature) in CHEAT_SIGNATURES {
        if lower.contains(needle) {
            return Some(ProcSignature {
                signature,
                event_type: "known_cheat_tool",
                severity: "high",
                score: 55,
                reason: "进程名/命令行命中已知作弊工具特征",
            });
        }
    }
    for (needle, signature) in DEBUG_SIGNATURES {
        if lower.contains(needle) {
            return Some(ProcSignature {
                signature,
                event_type: "debug_tool",
                severity: "medium",
                score: 35,
                reason: "进程名/命令行命中调试或逆向工具特征",
            });
        }
    }
    None
}

/// 判定一条 `maps` 行的可执行内存异常类别。
///
/// 返回 `(类别特征, 严重级, 权重, 原因)`；无可疑返回 `None`。
pub fn classify_exec_mapping(
    perms: &str,
    pathname: &str,
) -> Option<(&'static str, &'static str, i32, &'static str)> {
    if !perms.contains('x') {
        return None;
    }
    if perms.contains('w') {
        return Some((
            "rwx_mapping",
            "high",
            60,
            "存在可写且可执行的映射（W^X 被破坏）",
        ));
    }
    if pathname.starts_with("/memfd:") {
        return Some((
            "memfd_exec",
            "high",
            65,
            "执行了 memfd 匿名文件映射（内存落地代码）",
        ));
    }
    if pathname.ends_with("(deleted)") {
        return Some((
            "deleted_exec",
            "medium",
            45,
            "执行了已删除文件的可执行映射（落地即删）",
        ));
    }
    None
}

/// 无路径的可执行映射（仅计数，JIT 属正常）。
pub fn is_anonymous_exec_mapping(perms: &str, pathname: &str) -> bool {
    perms.contains('x') && pathname.is_empty()
}

/// 解析一条 `maps` 行 → `(地址区间, 权限, 路径名)`。
///
/// 行格式固定为 `地址区间 权限 偏移 设备 inode 路径名`，路径名之前总有 5 个字段
/// （匿名映射只是路径名为空）。必须跳过偏移/设备/inode 三个字段，否则
/// `/memfd:` 这类前缀判定会因为前面多出 `00000000 00:00 0` 而失配。
pub fn parse_maps_line(line: &str) -> Option<(String, String, String)> {
    let mut parts = line.split_whitespace();
    let range = parts.next()?.to_string();
    let perms = parts.next()?.to_string();
    if !range.contains('-') || perms.len() < 3 {
        return None;
    }
    let _offset = parts.next()?;
    let _dev = parts.next()?;
    let _inode = parts.next()?;
    let pathname = parts.collect::<Vec<_>>().join(" ");
    Some((range, perms, pathname))
}

// ---------------------------------------------------------------------------
// Linux 实现
// ---------------------------------------------------------------------------

#[cfg(target_os = "linux")]
mod imp {
    use super::*;
    use std::fs;

    /// 内核版本（`/proc/sys/kernel/osrelease`）。
    pub fn kernel_release() -> Option<String> {
        fs::read_to_string("/proc/sys/kernel/osrelease")
            .ok()
            .map(|s| s.trim().to_string())
    }

    /// 读取 `/proc/<pid>/status` 中的某字段（如 `Uid` / `PPid` / `TracerPid`）。
    pub fn status_value(text: &str, key: &str) -> Option<String> {
        text.lines()
            .find(|l| l.starts_with(key) && l.contains(':'))
            .and_then(|l| l.split_once(':'))
            .map(|(_, v)| v.trim().to_string())
    }

    /// 扫描 `/proc`，返回报告。`max_processes` 限制单轮开销（0 表示不限制）。
    pub fn scan(max_processes: usize) -> ProcScanReport {
        let mut report = ProcScanReport {
            kernel_release: kernel_release(),
            ..ProcScanReport::default()
        };
        let self_pid = std::process::id();

        let entries = match fs::read_dir("/proc") {
            Ok(e) => e,
            Err(err) => {
                report.unsupported_note = Some(if err.kind() == std::io::ErrorKind::NotFound {
                    "本机无 /proc（非 Linux 或无 procfs 挂载）"
                } else {
                    "读取 /proc 失败（权限不足）"
                });
                return report;
            }
        };

        let mut pids: Vec<u32> = entries
            .filter_map(|e| e.ok())
            .filter_map(|e| e.file_name().to_string_lossy().parse::<u32>().ok())
            .collect();
        pids.sort_unstable();
        if max_processes > 0 {
            pids.truncate(max_processes);
        }

        for pid in pids {
            if let Some(finding) = inspect_pid(pid, self_pid, &mut report) {
                report.findings.push(finding);
            }
        }

        // 本进程的反调试结论（与其他 /proc 读取同源，不额外引入依赖）
        if let Ok(status) = fs::read_to_string("/proc/self/status") {
            if let Some(raw) = status_value(&status, "TracerPid") {
                if let Ok(tracer) = raw.parse::<u32>() {
                    report.tracer_pid = Some(tracer);
                    report.debugger_present = tracer != 0;
                }
            }
        }

        report.suspicious_processes = report
            .findings
            .iter()
            .filter(|f| f.event_type == "known_cheat_tool" || f.event_type == "debug_tool")
            .count();
        report
    }

    /// 单进程检查：进程名/命令行命中，或 maps 出现可执行内存异常。
    fn inspect_pid(pid: u32, self_pid: u32, report: &mut ProcScanReport) -> Option<ProcFinding> {
        let comm = fs::read_to_string(format!("/proc/{pid}/comm"))
            .map(|s| s.trim().to_string())
            .unwrap_or_default();
        let cmdline = fs::read(format!("/proc/{pid}/cmdline"))
            .map(|b| {
                b.split(|c| *c == 0)
                    .filter(|s| !s.is_empty())
                    .map(|s| String::from_utf8_lossy(s).to_string())
                    .collect::<Vec<_>>()
                    .join(" ")
            })
            .unwrap_or_default();
        let exe = fs::read_link(format!("/proc/{pid}/exe"))
            .ok()
            .map(|p| p.to_string_lossy().to_string());
        let status = fs::read_to_string(format!("/proc/{pid}/status")).unwrap_or_default();
        let uid = status_value(&status, "Uid")
            .and_then(|v| {
                v.split_whitespace()
                    .next()
                    .and_then(|x| x.parse::<u32>().ok())
            })
            .unwrap_or(0);
        let ppid = status_value(&status, "PPid")
            .and_then(|v| v.trim().parse::<u32>().ok())
            .unwrap_or(0);
        report.scanned += 1;

        // 自己的进程不检查（避免「客户端命中自己的名字」这种荒谬告警）。
        if pid == self_pid {
            return None;
        }

        let entry = ProcEntry {
            pid,
            ppid,
            comm: comm.clone(),
            cmdline: cmdline.clone(),
            exe: exe.clone(),
            uid,
            kernel_thread: exe.is_none() && cmdline.is_empty(),
        };
        report.anonymous_exec_count += count_anonymous_exec(pid);

        // 只对用户态进程做特征匹配
        if entry.kernel_thread {
            return None;
        }

        // 1) 名称/命令行特征
        let name_blob = format!("{} {}", entry.comm, entry.cmdline);
        if let Some(sig) = signature_for(&name_blob) {
            return Some(ProcFinding {
                pid,
                name: entry.comm.clone(),
                signature: sig.signature.to_string(),
                event_type: sig.event_type,
                severity: sig.severity,
                score: sig.score,
                detail: vec![
                    ("reason".to_string(), sig.reason.to_string()),
                    ("comm".to_string(), entry.comm.clone()),
                    ("cmdline".to_string(), truncate(&entry.cmdline, 400)),
                    ("uid".to_string(), uid.to_string()),
                ],
            });
        }

        // 2) 可执行内存异常（取最严重的一条）
        let maps = fs::read_to_string(format!("/proc/{pid}/maps")).unwrap_or_default();
        let mut worst: Option<(&'static str, &'static str, i32, &'static str, String)> = None;
        for line in maps.lines() {
            let Some((range, perms, pathname)) = parse_maps_line(line) else {
                continue;
            };
            if let Some((kind, severity, score, reason)) = classify_exec_mapping(&perms, &pathname)
            {
                let better = worst.as_ref().map(|w| score > w.2).unwrap_or(true);
                if better {
                    worst = Some((
                        kind,
                        severity,
                        score,
                        reason,
                        format!("{range} {perms} {pathname}"),
                    ));
                }
            }
        }
        if let Some((kind, severity, score, reason, region)) = worst {
            return Some(ProcFinding {
                pid,
                name: entry.comm.clone(),
                signature: kind.to_string(),
                event_type: "suspicious_memory",
                severity,
                score,
                detail: vec![
                    ("reason".to_string(), reason.to_string()),
                    ("comm".to_string(), entry.comm.clone()),
                    ("memory_region".to_string(), region),
                    ("exe".to_string(), entry.exe.clone().unwrap_or_default()),
                ],
            });
        }
        None
    }

    fn count_anonymous_exec(pid: u32) -> usize {
        match fs::read_to_string(format!("/proc/{pid}/maps")) {
            Ok(maps) => maps
                .lines()
                .filter(|l| {
                    parse_maps_line(l)
                        .map(|(_, perms, pathname)| is_anonymous_exec_mapping(&perms, &pathname))
                        .unwrap_or(false)
                })
                .count(),
            Err(_) => 0,
        }
    }

    fn truncate(s: &str, max: usize) -> String {
        if s.chars().count() <= max {
            return s.to_string();
        }
        let cut: String = s.chars().take(max).collect();
        format!("{cut}…")
    }
}

#[cfg(target_os = "linux")]
pub use imp::{kernel_release, scan, status_value};

// ---------------------------------------------------------------------------
// 非 Linux 兜底：如实声明不可用
// ---------------------------------------------------------------------------

#[cfg(not(target_os = "linux"))]
mod fallback {
    use super::ProcScanReport;

    /// 非 Linux 平台没有 procfs；返回带说明的空报告，绝不伪造发现。
    pub fn scan(_max_processes: usize) -> ProcScanReport {
        ProcScanReport {
            unsupported_note: Some("procfs 回退扫描仅在 Linux 可用"),
            ..ProcScanReport::default()
        }
    }

    /// 见 [`scan`]。
    pub fn kernel_release() -> Option<String> {
        None
    }
}

#[cfg(not(target_os = "linux"))]
pub use fallback::{kernel_release, scan};

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cheat_name_hits_high() {
        let sig = signature_for("XRay-1.3.2").expect("应命中");
        assert_eq!(sig.event_type, "known_cheat_tool");
        assert_eq!(sig.severity, "high");
    }

    #[test]
    fn debug_tool_hits_medium() {
        let sig = signature_for("/usr/bin/gdb --pid 1234").expect("应命中");
        assert_eq!(sig.event_type, "debug_tool");
        assert_eq!(sig.severity, "medium");
    }

    #[test]
    fn normal_process_is_clean() {
        assert!(signature_for("java -Xmx4G -jar server.jar").is_none());
        assert!(signature_for("gnome-shell").is_none());
    }

    #[test]
    fn rwx_mapping_is_high_but_plain_anon_is_not() {
        let rwx = classify_exec_mapping("rwxp", "").expect("rwx 应命中");
        assert_eq!(rwx.0, "rwx_mapping");
        assert_eq!(rwx.1, "high");
        // 纯 r-xp 无路径（JIT 代码缓存）不算可疑。
        assert!(classify_exec_mapping("r-xp", "").is_none());
        assert!(is_anonymous_exec_mapping("r-xp", ""));
        assert!(!is_anonymous_exec_mapping("---p", ""));
    }

    #[test]
    fn memfd_and_deleted_mappings_are_flagged() {
        assert_eq!(
            classify_exec_mapping("r-xp", "/memfd:payload (deleted)").map(|c| c.0),
            Some("memfd_exec")
        );
        assert_eq!(
            classify_exec_mapping("r-xp", "/tmp/cheat (deleted)").map(|c| c.0),
            Some("deleted_exec")
        );
        // 正常库文件不应命中。
        assert!(classify_exec_mapping("r-xp", "/usr/lib/libc.so.6").is_none());
    }

    #[test]
    fn parses_maps_line_with_and_without_path() {
        let with_path =
            parse_maps_line("55d1a2b00000-55d1a2b01000 r-xp 00000000 08:01 1234 /usr/bin/java")
                .expect("应可解析");
        assert_eq!(with_path.1, "r-xp");
        assert_eq!(with_path.2, "/usr/bin/java");
        let anon =
            parse_maps_line("7f8a1c000000-7f8a1c021000 rwxp 00000000 00:00 0").expect("应可解析");
        assert_eq!(anon.1, "rwxp");
        assert_eq!(anon.2, "");
    }

    #[test]
    fn scan_runs_on_linux_and_reports_platform() {
        let report = scan(32);
        if cfg!(target_os = "linux") {
            assert!(report.unsupported_note.is_none());
            assert!(report.scanned > 0);
            assert!(report.kernel_release.is_some());
        } else {
            assert!(report.unsupported_note.is_some());
            assert_eq!(report.scanned, 0);
        }
    }
}
