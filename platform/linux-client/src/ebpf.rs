//! eBPF 事件源：连接 `platform/kernel-linux` userspace loader 的 UNIX socket，
//! 按行（NDJSON）读取内核侧事件。
//!
//! 断线策略：socket 消失/读失败后**不忙循环**——退避计时器把重连间隔从
//! 500ms 逐步翻倍到 30s，连上后立刻复位。loader 未启动时进程保持安静地等待，
//! 不会把 CPU 打满，也不会刷日志。
//!
//! 与 loader 的接口契约（两端都以此为准）：
//! * 传输：`SOCK_STREAM` 的 UNIX socket，默认 `/run/pacc/pacc-ldm.sock`
//!   （可用 `pacc.client.ebpf.socket` / `PACC_CLIENT_EBPF_SOCKET` 改）；
//! * 编码：每行一个 JSON 对象（NDJSON），`\n` 为帧分隔；
//! * 字段（全部可选，宽容解析）：`event`/`event_type`、`pid`、`ppid`、
//!   `comm`/`process_name`、`path`/`exe`、`uid`、`severity`、`ts`/`timestamp`、
//!   `detail`（字符串或对象）。认不出的字段忽略，认不出的行只计数不转发。
//!
//! 非 Unix 平台上本模块只保留退避状态机，`poll` 恒返回空（诚实声明不可用）。
//! 那些只在 Unix 分支里被用到的解析/退避代码，在别的平台编译时就允许「未被使用」，
//! 免得为了消灭 cfg 警告去牺牲 Linux 上的实现。

#![cfg_attr(not(unix), allow(dead_code))]

use pacc_core::json;
use std::time::{Duration, Instant};

/// 单行 NDJSON 的最大长度；超过则丢弃半截行，避免畸形输入把内存撑爆。
const MAX_LINE_BYTES: usize = 64 * 1024;
/// 单次读取的缓冲区。
const READ_CHUNK: usize = 8192;

/// loader 事件（宽容解析结果）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct LoaderEvent {
    /// 原始 NDJSON 行（取证保留，便于人工核对）。
    pub raw: String,
    /// 事件类型（`event` / `event_type` / `type`）。
    pub event: String,
    pub pid: Option<u32>,
    pub ppid: Option<u32>,
    pub comm: Option<String>,
    pub exe: Option<String>,
    pub uid: Option<u32>,
    pub severity: Option<String>,
    pub ts_millis: Option<u64>,
    /// 明细（对象则重新编码为紧凑 JSON 串）。
    pub detail: Option<String>,
}

impl LoaderEvent {
    /// 从一行 NDJSON 解析；非 JSON 或缺 `event` 字段时返回 `None`。
    pub fn from_line(line: &str) -> Option<LoaderEvent> {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            return None;
        }
        let value = json::parse(trimmed).ok()?;
        let event = first_str(&value, &["event", "event_type", "type"])?;
        Some(LoaderEvent {
            raw: trimmed.to_string(),
            event,
            pid: first_u32(&value, &["pid", "tgid"]),
            ppid: first_u32(&value, &["ppid"]),
            comm: first_str(&value, &["comm", "process_name", "name"]),
            exe: first_str(&value, &["path", "exe", "binary"]),
            uid: first_u32(&value, &["uid"]),
            severity: first_str(&value, &["severity", "level"]),
            ts_millis: first_u64(&value, &["ts", "timestamp", "time", "ts_millis"]),
            detail: match value.get("detail") {
                Some(json::Value::String(s)) => Some(s.clone()),
                Some(other) => Some(other.encode()),
                None => None,
            },
        })
    }

    /// 由事件类型推断严重级（loader 未显式给出时使用）。
    pub fn inferred_severity(&self) -> &'static str {
        let name = self.event.to_ascii_lowercase();
        let suspicious = [
            "ptrace",
            "pvmread",
            "process_vm_readv",
            "devmem",
            "/dev/mem",
            "inject",
            "dma",
            "exec_anon",
            "memfd",
            "unlink_self",
            "setuid",
        ];
        if suspicious.iter().any(|s| name.contains(s)) {
            "high"
        } else {
            "low"
        }
    }

    /// 是否值得上报（loader 判定为 high/medium，或类型本身可疑）。
    pub fn is_suspicious(&self) -> bool {
        match self.severity.as_deref() {
            Some("high") | Some("medium") | Some("critical") => true,
            Some(_) => false,
            None => self.inferred_severity() == "high",
        }
    }
}

fn first_str(value: &json::Value, keys: &[&str]) -> Option<String> {
    keys.iter()
        .find_map(|k| value.get(k).and_then(json::Value::as_str))
        .map(|s| s.to_string())
}

fn first_u32(value: &json::Value, keys: &[&str]) -> Option<u32> {
    keys.iter()
        .find_map(|k| value.get(k).and_then(json::Value::as_i64))
        .filter(|n| *n >= 0 && *n <= u32::MAX as i64)
        .map(|n| n as u32)
}

fn first_u64(value: &json::Value, keys: &[&str]) -> Option<u64> {
    keys.iter()
        .find_map(|k| value.get(k).and_then(json::Value::as_f64))
        .filter(|n| n.is_finite() && *n >= 0.0)
        .map(|n| n as u64)
}

/// 指数退避计时器（不忙循环的核心）。
#[derive(Debug, Clone)]
pub struct Backoff {
    base: Duration,
    max: Duration,
    current: Duration,
    next_at: Instant,
}

impl Backoff {
    pub fn new(base: Duration, max: Duration) -> Self {
        Self {
            base,
            max,
            current: base,
            next_at: Instant::now(),
        }
    }

    /// 现在是否可以尝试重连。
    pub fn ready(&self, now: Instant) -> bool {
        now >= self.next_at
    }

    /// 连接成功：间隔复位。
    pub fn on_success(&mut self, now: Instant) {
        self.current = self.base;
        self.next_at = now;
    }

    /// 连接/读取失败：间隔翻倍后安排下一次。
    pub fn on_failure(&mut self, now: Instant) {
        self.next_at = now + self.current;
        let doubled = self.current.saturating_mul(2);
        self.current = if doubled > self.max {
            self.max
        } else {
            doubled
        };
    }

    /// 距离下次可尝试还有多久（已就绪时为 0）。
    pub fn remaining(&self, now: Instant) -> Duration {
        self.next_at.saturating_duration_since(now)
    }
}

/// UNIX socket 是否存在且为 socket 类型（探测用；非 unix 恒 false）。
#[cfg(unix)]
pub fn socket_exists(path: &str) -> bool {
    use std::os::unix::fs::FileTypeExt;
    std::fs::metadata(path)
        .map(|m| m.file_type().is_socket())
        .unwrap_or(false)
}

/// 见 [`socket_exists`]。
#[cfg(not(unix))]
pub fn socket_exists(_path: &str) -> bool {
    false
}

// ---------------------------------------------------------------------------
// Unix 实现
// ---------------------------------------------------------------------------

#[cfg(unix)]
pub use unix_impl::EbpfSource;

#[cfg(unix)]
mod unix_impl {
    use super::*;
    use std::io::{ErrorKind, Read};
    use std::os::unix::net::UnixStream;

    /// eBPF 事件源：持有 UNIX socket 连接与退避状态。
    pub struct EbpfSource {
        socket_path: String,
        stream: Option<UnixStream>,
        buf: Vec<u8>,
        backoff: Backoff,
        lines_total: u64,
        parse_errors: u64,
        last_error: Option<String>,
    }

    impl EbpfSource {
        pub fn new(socket_path: impl Into<String>) -> Self {
            Self {
                socket_path: socket_path.into(),
                stream: None,
                buf: Vec::with_capacity(READ_CHUNK),
                backoff: Backoff::new(Duration::from_millis(500), Duration::from_secs(30)),
                lines_total: 0,
                parse_errors: 0,
                last_error: None,
            }
        }

        pub fn is_connected(&self) -> bool {
            self.stream.is_some()
        }

        pub fn lines_total(&self) -> u64 {
            self.lines_total
        }

        pub fn parse_errors(&self) -> u64 {
            self.parse_errors
        }

        pub fn last_error(&self) -> Option<&str> {
            self.last_error.as_deref()
        }

        pub fn backoff_remaining(&self, now: Instant) -> Duration {
            self.backoff.remaining(now)
        }

        /// 读取当前可用的事件；无数据或断线时返回空（不阻塞、不忙循环）。
        pub fn poll(&mut self, now: Instant) -> Vec<LoaderEvent> {
            if self.stream.is_none() {
                if !self.backoff.ready(now) {
                    return Vec::new();
                }
                self.try_connect(now);
                if self.stream.is_none() {
                    return Vec::new();
                }
            }
            self.drain(now)
        }

        fn try_connect(&mut self, now: Instant) {
            match UnixStream::connect(&self.socket_path) {
                Ok(stream) => {
                    if let Err(e) = stream.set_nonblocking(true) {
                        self.last_error = Some(format!("设置非阻塞失败: {e}"));
                        self.backoff.on_failure(now);
                        return;
                    }
                    self.stream = Some(stream);
                    self.buf.clear();
                    self.backoff.on_success(now);
                    self.last_error = None;
                }
                Err(e) => {
                    self.last_error = Some(format!("连接 {} 失败: {e}", self.socket_path));
                    self.backoff.on_failure(now);
                }
            }
        }

        fn drain(&mut self, now: Instant) -> Vec<LoaderEvent> {
            let mut chunk = [0u8; READ_CHUNK];
            loop {
                let read_result = match self.stream.as_mut() {
                    Some(s) => s.read(&mut chunk),
                    None => return self.take_lines(),
                };
                match read_result {
                    Ok(0) => {
                        // 对端关闭：丢弃连接并退避，等 loader 回来。
                        self.disconnect(now, "对端关闭连接");
                        return self.take_lines();
                    }
                    Ok(n) => {
                        if self.buf.len() + n <= MAX_LINE_BYTES {
                            self.buf.extend_from_slice(&chunk[..n]);
                        } else {
                            // 行长超限：整段丢弃，避免无限增长。
                            self.buf.clear();
                            self.parse_errors += 1;
                        }
                    }
                    Err(e) if e.kind() == ErrorKind::WouldBlock => break,
                    Err(e) if e.kind() == ErrorKind::Interrupted => continue,
                    Err(e) => {
                        self.disconnect(now, &format!("读取失败: {e}"));
                        return self.take_lines();
                    }
                }
            }
            self.take_lines()
        }

        fn disconnect(&mut self, now: Instant, reason: &str) {
            self.stream = None;
            self.last_error = Some(reason.to_string());
            self.backoff.on_failure(now);
        }

        /// 把缓冲区里完整的行取出来解析，半截行留在缓冲区等下次。
        fn take_lines(&mut self) -> Vec<LoaderEvent> {
            let mut events = Vec::new();
            while let Some(idx) = self.buf.iter().position(|b| *b == b'\n') {
                let line: Vec<u8> = self.buf.drain(..=idx).collect();
                // 去掉行尾 \n 与可能的 \r，再按 UTF-8 解析；非法 UTF-8 只计数。
                let end = line.len().saturating_sub(1);
                let bytes = &line[..end];
                let bytes = if bytes.last() == Some(&b'\r') {
                    &bytes[..bytes.len() - 1]
                } else {
                    bytes
                };
                match std::str::from_utf8(bytes) {
                    Ok(text) => match LoaderEvent::from_line(text) {
                        Some(ev) => {
                            self.lines_total += 1;
                            events.push(ev);
                        }
                        None => self.parse_errors += 1,
                    },
                    Err(_) => self.parse_errors += 1,
                }
            }
            events
        }
    }
}

// ---------------------------------------------------------------------------
// 非 Unix 兜底：如实声明不可用，绝不伪造事件
// ---------------------------------------------------------------------------

#[cfg(not(unix))]
pub use non_unix_impl::EbpfSource;

#[cfg(not(unix))]
mod non_unix_impl {
    use super::*;

    /// 非 Unix 平台没有 `AF_UNIX` 流式套接字（Windows 的 AF_UNIX 语义不同且
    /// 与 loader 不兼容），因此本实现只保留状态与退避，`poll` 恒返回空。
    pub struct EbpfSource {
        /// 保留路径只为与 Unix 实现同形；本平台不建连接，因此读不到它。
        #[allow(dead_code)]
        socket_path: String,
        backoff: Backoff,
    }

    impl EbpfSource {
        pub fn new(socket_path: impl Into<String>) -> Self {
            Self {
                socket_path: socket_path.into(),
                backoff: Backoff::new(Duration::from_millis(500), Duration::from_secs(30)),
            }
        }

        pub fn is_connected(&self) -> bool {
            false
        }

        pub fn lines_total(&self) -> u64 {
            0
        }

        pub fn parse_errors(&self) -> u64 {
            0
        }

        pub fn last_error(&self) -> Option<&str> {
            Some("当前平台不支持 UNIX socket 事件源（仅 Linux 部署可用）")
        }

        pub fn backoff_remaining(&self, now: Instant) -> Duration {
            self.backoff.remaining(now)
        }

        pub fn poll(&mut self, _now: Instant) -> Vec<LoaderEvent> {
            Vec::new()
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_full_loader_event() {
        let line = r#"{"ts":1712000000,"event":"ptrace","pid":4242,"ppid":1,"comm":"java","path":"/usr/lib/jvm/bin/java","uid":1000,"severity":"high","detail":{"target":1234}}"#;
        let ev = LoaderEvent::from_line(line).expect("应可解析");
        assert_eq!(ev.event, "ptrace");
        assert_eq!(ev.pid, Some(4242));
        assert_eq!(ev.comm.as_deref(), Some("java"));
        assert_eq!(ev.uid, Some(1000));
        assert_eq!(ev.severity.as_deref(), Some("high"));
        assert_eq!(ev.detail.as_deref(), Some(r#"{"target":1234}"#));
        assert!(ev.is_suspicious());
    }

    #[test]
    fn parses_minimal_event_and_infers_severity() {
        let ev = LoaderEvent::from_line(r#"{"type":"exec_anon","pid":7}"#).expect("应可解析");
        assert_eq!(ev.event, "exec_anon");
        assert_eq!(ev.inferred_severity(), "high");
        assert!(ev.is_suspicious());
    }

    #[test]
    fn rejects_non_json_and_missing_type() {
        assert!(LoaderEvent::from_line("not json").is_none());
        assert!(LoaderEvent::from_line(r#"{"pid":1}"#).is_none());
        assert!(LoaderEvent::from_line("").is_none());
    }

    #[test]
    fn benign_event_is_not_suspicious() {
        let ev = LoaderEvent::from_line(r#"{"event":"connect","pid":9,"severity":"low"}"#)
            .expect("应可解析");
        assert!(!ev.is_suspicious());
    }

    #[test]
    fn backoff_doubles_then_caps() {
        let mut b = Backoff::new(Duration::from_millis(500), Duration::from_secs(4));
        let t0 = Instant::now();
        assert!(b.ready(t0), "刚构造完应可立即尝试");
        assert_eq!(b.remaining(t0), Duration::ZERO);

        // `remaining` 在失败后等于失败前的间隔，等价于观察当前的退避量。
        b.on_failure(t0);
        assert_eq!(b.remaining(t0), Duration::from_millis(500));
        assert!(!b.ready(t0), "失败后必须等待，不允许立刻重试");

        b.on_failure(t0);
        assert_eq!(b.remaining(t0), Duration::from_secs(1));
        b.on_failure(t0);
        assert_eq!(b.remaining(t0), Duration::from_secs(2));
        b.on_failure(t0);
        assert_eq!(b.remaining(t0), Duration::from_secs(4), "退避必须封顶");
        b.on_failure(t0);
        assert_eq!(b.remaining(t0), Duration::from_secs(4), "封顶后不再增长");

        b.on_success(t0);
        assert_eq!(b.remaining(t0), Duration::ZERO);
        assert!(b.ready(t0), "连上后应立刻复位");
    }

    #[test]
    fn missing_socket_is_not_connected_and_poll_is_quiet() {
        let mut src = EbpfSource::new("/run/pacc/definitely-missing.sock");
        let now = Instant::now();
        assert!(src.poll(now).is_empty());
        assert!(!src.is_connected());
        assert!(src.last_error().is_some());
        // 退避只在 Unix 实现里发生：非 Unix 是诚实 stub，根本不会去连接。
        #[cfg(unix)]
        assert!(
            !src.backoff_remaining(now).is_zero(),
            "连接失败后必须退避，不允许忙循环"
        );
    }
}
