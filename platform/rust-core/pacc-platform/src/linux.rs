//! Linux 平台实现（设计文档 §4.2.1 / §3.3）。
//!
//! 纯标准库实现，数据来源全部是内核暴露的只读接口：
//! - 进程枚举：`/proc/<pid>/{comm,cmdline,exe,status}`；
//! - 内存扫描：`/proc/<pid>/maps` 定位可读区 + `/proc/<pid>/mem` 分块读取；
//! - 输入采样：`/dev/input/event*` 以 `O_NONBLOCK` 非阻塞读取 `struct input_event`；
//! - 反调试：`/proc/self/status` 的 `TracerPid`；
//! - 安装完整性：清单文件的大小 + CRC32。
//!
//! 所有写入性操作一律不做；`O_NONBLOCK` 用常量 `0x800` 直接传入 `custom_flags`，
//! 以避免引入 `libc` 依赖（该值在 Linux 各架构上恒为 `0x800`）。

use std::fs::{self, File, OpenOptions};
use std::io::{Read, Seek, SeekFrom};
use std::os::unix::fs::OpenOptionsExt;
use std::path::Path;

use crate::{
    crc32, AntiDebugReport, Capabilities, InputKind, InputSample, IntegrityManifest,
    IntegrityReport, MemoryHit, Platform, PlatformError, ProcessInfo,
};

/// Linux `O_NONBLOCK`（`include/uapi/asm-generic/fcntl.h`，各架构一致）。
const O_NONBLOCK: i32 = 0x800;
/// 单次内存扫描读取块大小。
const SCAN_CHUNK: usize = 1 << 20;
/// 单次内存扫描的总读取上限（防止对超大进程长时间驻留）。
const SCAN_MAX_BYTES: u64 = 512 << 20;
/// 单次输入采样的最多返回事件数。
const INPUT_SAMPLE_CAP: usize = 512;

pub struct LinuxPlatform;

impl LinuxPlatform {
    pub fn new() -> Self {
        Self
    }
}

impl Default for LinuxPlatform {
    fn default() -> Self {
        Self::new()
    }
}

impl Platform for LinuxPlatform {
    fn name(&self) -> &'static str {
        if cfg!(target_pointer_width = "64") {
            "linux_x64"
        } else {
            "linux_x86"
        }
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities {
            process_enum: true,
            memory_scan: true,
            input_sampling: true,
            anti_debug: true,
            install_integrity: true,
        }
    }

    fn enumerate_processes(&self) -> Result<Vec<ProcessInfo>, PlatformError> {
        let mut out = Vec::new();
        let dir = fs::read_dir("/proc").map_err(PlatformError::from)?;
        for entry in dir.flatten() {
            let name = entry.file_name();
            let Some(name) = name.to_str() else { continue };
            let Ok(pid) = name.parse::<u32>() else {
                continue;
            };
            let base = Path::new("/proc").join(name);

            let comm = read_trimmed(&base.join("comm")).unwrap_or_default();
            let cmdline = read_cmdline(&base.join("cmdline"));
            let exe = fs::read_link(base.join("exe"))
                .ok()
                .and_then(|p| p.to_str().map(str::to_string));
            let status = fs::read_to_string(base.join("status")).unwrap_or_default();
            let ppid = parse_status_u32(&status, "PPid:").unwrap_or(0);
            let uid = parse_status_u32(&status, "Uid:").unwrap_or(0);

            // 内核线程无用户态映像：exe 链接为空且 cmdline 为空，comm 常带方括号语义。
            let kernel_thread = exe.is_none() && cmdline.is_empty();
            out.push(ProcessInfo {
                pid,
                ppid,
                name: comm,
                exe,
                cmdline,
                uid,
                kernel_thread,
            });
        }
        Ok(out)
    }

    fn scan_memory(&self, pid: u32, pattern: &[u8]) -> Result<Vec<MemoryHit>, PlatformError> {
        if pattern.is_empty() {
            return Ok(Vec::new());
        }
        let maps_path = format!("/proc/{pid}/maps");
        let maps = fs::read_to_string(&maps_path).map_err(PlatformError::from)?;
        let mut mem = File::open(format!("/proc/{pid}/mem")).map_err(PlatformError::from)?;

        let mut hits = Vec::new();
        let mut budget = SCAN_MAX_BYTES;
        // 只读分块扫描可读区；写入/命中均不触碰目标进程。
        for line in maps.lines() {
            let Some(region) = parse_maps_line(line) else {
                continue;
            };
            // perms 第 1 位为 'r' 才可读。
            if !region.perms.starts_with('r') {
                continue;
            }
            let mut offset = region.start;
            while offset < region.end && budget > 0 {
                let want = SCAN_CHUNK.min((region.end - offset) as usize);
                let want = want.min(budget as usize);
                if want == 0 {
                    break;
                }
                let mut buf = vec![0u8; want];
                if mem.seek(SeekFrom::Start(offset)).is_err() {
                    break;
                }
                match mem.read_exact(&mut buf) {
                    Ok(()) => {}
                    // 空洞 / 不可读页：跳过该区剩余部分，继续下一个映射区。
                    Err(_) => break,
                }
                budget = budget.saturating_sub(want as u64);
                if let Some(idx) = find_subslice(&buf, pattern) {
                    hits.push(MemoryHit {
                        pid,
                        address: offset + idx as u64,
                        region: region.describe(),
                        bytes: pattern.to_vec(),
                    });
                }
                offset += want as u64;
            }
            if budget == 0 {
                break;
            }
        }
        Ok(hits)
    }

    fn sample_input_events(&self, window_ms: u64) -> Result<Vec<InputSample>, PlatformError> {
        let dir = fs::read_dir("/dev/input").map_err(PlatformError::from)?;
        let mut devices: Vec<_> = dir
            .flatten()
            .map(|e| e.path())
            .filter(|p| {
                p.file_name()
                    .and_then(|n| n.to_str())
                    .is_some_and(|n| n.starts_with("event"))
            })
            .collect();
        devices.sort();

        let mut samples = Vec::new();
        let mut denied: Option<String> = None;
        for dev in devices {
            let opened = OpenOptions::new()
                .read(true)
                .custom_flags(O_NONBLOCK)
                .open(&dev);
            let mut file = match opened {
                Ok(f) => f,
                Err(e) => {
                    // 非 root 常见：/dev/input/event* 权限 0600 root:input。
                    denied = Some(format!("{}: {e}", dev.display()));
                    continue;
                }
            };
            let mut raw = Vec::new();
            // 非阻塞读取：EAGAIN(WouldBlock) 立即返回已读数据，不会挂起。
            let _ = file.read_to_end(&mut raw);
            samples.extend(parse_input_events(&raw));
            if samples.len() >= INPUT_SAMPLE_CAP {
                break;
            }
        }

        if samples.is_empty() {
            if let Some(reason) = denied {
                return Err(PlatformError::Denied(reason));
            }
            // 无输入设备或本窗口无事件：这是合法结果（非错误）。
            return Ok(Vec::new());
        }

        if window_ms > 0 {
            let max_ts = samples.iter().map(|s| s.ts_millis).max().unwrap_or(0);
            let floor = max_ts.saturating_sub(window_ms);
            samples.retain(|s| s.ts_millis >= floor);
        }
        samples.truncate(INPUT_SAMPLE_CAP);
        Ok(samples)
    }

    fn anti_debug_probe(&self) -> Result<AntiDebugReport, PlatformError> {
        let status = fs::read_to_string("/proc/self/status").map_err(PlatformError::from)?;
        let tracer = parse_status_u32(&status, "TracerPid:").unwrap_or(0);
        let mut findings = Vec::new();
        if tracer != 0 {
            findings.push(format!("tracer_pid={tracer}"));
        }
        // Yama ptrace_scope=1 时非父子进程无法 attach，属常见加固项，仅作证据记录。
        if let Ok(scope) = fs::read_to_string("/proc/sys/kernel/yama/ptrace_scope") {
            if scope.trim() == "0" {
                findings.push("yama_ptrace_scope=0".to_string());
            }
        }
        Ok(AntiDebugReport {
            debugger_present: tracer != 0,
            tracer_pid: if tracer == 0 { None } else { Some(tracer) },
            findings,
        })
    }

    fn verify_install_integrity(
        &self,
        manifest: &IntegrityManifest,
    ) -> Result<IntegrityReport, PlatformError> {
        let mut mismatches = Vec::new();
        for entry in &manifest.entries {
            let data = match fs::read(&entry.path) {
                Ok(d) => d,
                Err(e) => {
                    mismatches.push(format!("{}: 读取失败 {e}", entry.path));
                    continue;
                }
            };
            let actual_crc = crc32(&data);
            if data.len() as u64 != entry.expected_size || actual_crc != entry.expected_crc32 {
                mismatches.push(format!(
                    "{}: size {} -> {}, crc {:08x} -> {:08x}",
                    entry.path,
                    data.len(),
                    entry.expected_size,
                    actual_crc,
                    entry.expected_crc32
                ));
            }
        }
        Ok(IntegrityReport {
            matched: mismatches.is_empty(),
            checked: manifest.entries.len(),
            mismatches,
        })
    }
}

/// `/proc/<pid>/maps` 一行的解析结果。
struct MapsRegion {
    start: u64,
    end: u64,
    perms: String,
    rest: String,
}

impl MapsRegion {
    fn describe(&self) -> String {
        format!(
            "{:x}-{:x} {} {}",
            self.start, self.end, self.perms, self.rest
        )
    }
}

fn parse_maps_line(line: &str) -> Option<MapsRegion> {
    let mut it = line.split_whitespace();
    let range = it.next()?;
    let perms = it.next()?.to_string();
    let _offset = it.next()?;
    let _dev = it.next()?;
    let _inode = it.next()?;
    let rest = it.collect::<Vec<_>>().join(" ");
    let (start, end) = range.split_once('-')?;
    Some(MapsRegion {
        start: u64::from_str_radix(start, 16).ok()?,
        end: u64::from_str_radix(end, 16).ok()?,
        perms,
        rest,
    })
}

/// 朴素子串搜索（模式串通常很短，无需 BM/KMP）。
fn find_subslice(hay: &[u8], needle: &[u8]) -> Option<usize> {
    if needle.is_empty() || needle.len() > hay.len() {
        return None;
    }
    hay.windows(needle.len()).position(|w| w == needle)
}

fn read_trimmed(path: &Path) -> Option<String> {
    fs::read_to_string(path).ok().map(|s| s.trim().to_string())
}

fn read_cmdline(path: &Path) -> String {
    match fs::read(path) {
        Ok(bytes) => {
            let text: String = bytes
                .split(|&b| b == 0)
                .filter(|seg| !seg.is_empty())
                .map(|seg| String::from_utf8_lossy(seg).into_owned())
                .collect::<Vec<_>>()
                .join(" ");
            text
        }
        Err(_) => String::new(),
    }
}

/// 从 `/proc/<pid>/status` 文本里取形如 `Key:\tvalue` 的首个数值。
fn parse_status_u32(status: &str, key: &str) -> Option<u32> {
    for line in status.lines() {
        if let Some(rest) = line.strip_prefix(key) {
            return rest.split_whitespace().next().and_then(|v| v.parse().ok());
        }
    }
    None
}

/// 解析 `struct input_event` 记录流（x86_64：24 字节/条）。
fn parse_input_events(raw: &[u8]) -> Vec<InputSample> {
    const REC: usize = 24;
    let mut out = Vec::new();
    for chunk in raw.as_chunks::<REC>().0 {
        let sec = i64::from_ne_bytes(chunk[0..8].try_into().unwrap());
        let usec = i64::from_ne_bytes(chunk[8..16].try_into().unwrap());
        let ev_type = u16::from_ne_bytes(chunk[16..18].try_into().unwrap());
        let code = u16::from_ne_bytes(chunk[18..20].try_into().unwrap());
        let value = i32::from_ne_bytes(chunk[20..24].try_into().unwrap());
        if sec < 0 {
            continue;
        }
        let ts_millis = (sec as u64).saturating_mul(1000) + (usec.max(0) as u64) / 1000;
        // EV_SYN(0) 只做批次分隔，不承载输入语义。
        let kind = match ev_type {
            1 => InputKind::Key,       // EV_KEY
            2 => InputKind::MouseMove, // EV_REL
            _ => continue,
        };
        out.push(InputSample {
            kind,
            code,
            value,
            ts_millis,
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_line_parses() {
        let r = parse_maps_line("55d0-5600 r-xp 00000000 08:01 1234 /usr/bin/foo").unwrap();
        assert_eq!(r.start, 0x55d0);
        assert_eq!(r.end, 0x5600);
        assert_eq!(r.perms, "r-xp");
        assert_eq!(r.rest, "/usr/bin/foo");
    }

    #[test]
    fn status_value_parses() {
        let s = "Name:\tfoo\nTracerPid:\t0\nUid:\t1000\t1000\t1000\t1000\n";
        assert_eq!(parse_status_u32(s, "TracerPid:"), Some(0));
        assert_eq!(parse_status_u32(s, "Uid:"), Some(1000));
    }
}
