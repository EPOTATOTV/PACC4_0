//! 平台层公共数据类型。
//!
//! 这些结构是「端侧事件协议」在 Rust 侧的最小落地：所有平台实现返回同一组类型，
//! 上层 `pacc-core` 据此折算为统一的
//! [`DetectionEvent`](https://example.invalid)。

use std::fmt;

/// 平台层错误。
///
/// [`PlatformError::Unsupported`] 是**诚实的不可用信号**：当某平台无法真正实现某项能力时，
/// 一律返回它而不是伪造成功——伪造成功会让上层把「没有数据」误判为「数据正常」。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PlatformError {
    /// 该平台在当前构建/权限下不提供此能力。
    Unsupported(&'static str),
    /// 底层 IO 失败（文件不存在、读取中断等）。
    Io(String),
    /// 权限不足（如无 `CAP_SYS_PTRACE` 无法读 `/proc/<pid>/mem`）。
    Denied(String),
}

impl fmt::Display for PlatformError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            PlatformError::Unsupported(what) => write!(f, "平台不支持: {what}"),
            PlatformError::Io(msg) => write!(f, "IO 错误: {msg}"),
            PlatformError::Denied(msg) => write!(f, "权限不足: {msg}"),
        }
    }
}

impl std::error::Error for PlatformError {}

impl From<std::io::Error> for PlatformError {
    fn from(e: std::io::Error) -> Self {
        use std::io::ErrorKind;
        match e.kind() {
            ErrorKind::PermissionDenied => PlatformError::Denied(e.to_string()),
            _ => PlatformError::Io(e.to_string()),
        }
    }
}

/// 平台能力声明。
///
/// 上层据此决定「探测失败」还是「本就没有这项能力」，避免把不可用当成阴性结论。
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct Capabilities {
    /// 进程枚举。
    pub process_enum: bool,
    /// 目标进程内存扫描。
    pub memory_scan: bool,
    /// 输入事件采样（键鼠）。
    pub input_sampling: bool,
    /// 反调试探针。
    pub anti_debug: bool,
    /// 安装完整性校验。
    pub install_integrity: bool,
}

/// 进程快照条目。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct ProcessInfo {
    pub pid: u32,
    pub ppid: u32,
    /// 进程名（Linux 取 `/proc/<pid>/comm`）。
    pub name: String,
    /// 可执行文件绝对路径（取不到为 `None`，如内核线程）。
    pub exe: Option<String>,
    /// 命令行（空格连接；内核线程为空）。
    pub cmdline: String,
    pub uid: u32,
    /// 是否为内核线程（无用户态映像）。
    pub kernel_thread: bool,
}

/// 内存扫描命中。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MemoryHit {
    pub pid: u32,
    pub address: u64,
    /// 命中所在内存区的可读描述（`start-end perms pathname`）。
    pub region: String,
    /// 命中处的原始字节（与扫描模式等长）。
    pub bytes: Vec<u8>,
}

/// 输入事件类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InputKind {
    Key,
    MouseMove,
    MouseButton,
}

/// 输入采样事件（Linux 直接映射 `struct input_event`）。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct InputSample {
    pub kind: InputKind,
    /// 事件码（EV_KEY 为键码，EV_REL 为轴码）。
    pub code: u16,
    /// 值（按键 1/0，相对位移量）。
    pub value: i32,
    /// 事件时间戳（毫秒，基于系统单调时钟近似）。
    pub ts_millis: u64,
}

/// 反调试探针结论。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct AntiDebugReport {
    pub debugger_present: bool,
    /// Linux：`/proc/self/status` 的 `TracerPid`（0 表示无跟踪者）。
    pub tracer_pid: Option<u32>,
    /// 命中的证据条目（用于取证描述，不含原始全量数据）。
    pub findings: Vec<String>,
}

/// 参与完整性校验的文件条目。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IntegrityEntry {
    pub path: String,
    pub expected_size: u64,
    /// 期望的 CRC32（IEEE 多项式）。选 CRC32 而非 SHA-256 是为了不引入加密依赖；
    /// 生产发布链路的密码学强度校验由 `deploy/pipe-rust` 的 SHA-256/HMAC 承担。
    pub expected_crc32: u32,
}

/// 安装完整性清单。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct IntegrityManifest {
    pub entries: Vec<IntegrityEntry>,
}

/// 安装完整性校验结论。
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct IntegrityReport {
    pub matched: bool,
    pub checked: usize,
    /// 不一致条目描述（`path: size/crc 实际 -> 期望`）。
    pub mismatches: Vec<String>,
}

/// CRC32（IEEE 802.3，反射多项式 0xEDB88320）。
pub fn crc32(bytes: &[u8]) -> u32 {
    let mut crc = 0xFFFF_FFFFu32;
    for &b in bytes {
        crc ^= b as u32;
        for _ in 0..8 {
            let mask = (crc & 1).wrapping_neg();
            crc = (crc >> 1) ^ (0xEDB8_8320 & mask);
        }
    }
    !crc
}

#[cfg(test)]
mod tests {
    use super::crc32;

    #[test]
    fn crc32_known_answer() {
        // 标准测试向量：CRC32("123456789") = 0xCBF43926
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
    }
}
