//! PACC 平台抽象层（设计文档 §4.2.1 的 `pacc-platform`）。
//!
//! 定义跨平台检测核心与各操作系统原生实现之间的唯一契约 [`Platform`]：
//! 进程枚举、内存扫描、输入事件采样、反调试探针、安装完整性校验。
//! 每个受支持平台在 cfg 分支下提供一份实现；无法落地的平台返回
//! [`PlatformError::Unsupported`]，**绝不伪造成功**。
//!
//! 设计约束：
//! 1. 纯标准库，零第三方依赖（含 Windows/移动端：宁可如实声明不支持）。
//! 2. 所有 Linux 专有逻辑由 `#[cfg(target_os = "linux")]` 守护，Windows 上仍可编译。
//! 3. 只读采样：本层不做任何写入目标进程/系统的动作。

mod types;

#[cfg(target_os = "android")]
mod android;
/// HarmonyOS 的官方 Rust 目标是 `aarch64-unknown-linux-ohos`：`target_os = "linux"`、
/// `target_env = "ohos"`，因此用 `target_env` 区分，避免与 Linux 实现同时启用。
#[cfg(target_env = "ohos")]
mod harmonyos;
#[cfg(target_os = "ios")]
mod ios;
#[cfg(all(target_os = "linux", not(target_env = "ohos")))]
mod linux;
#[cfg(target_os = "windows")]
mod windows;

pub use types::{
    crc32, AntiDebugReport, Capabilities, InputKind, InputSample, IntegrityEntry,
    IntegrityManifest, IntegrityReport, MemoryHit, PlatformError, ProcessInfo,
};

/// 平台抽象：一次完整检测所需的全部原生能力。
///
/// 实现者必须保证方法可重入且不 panic；失败以 [`PlatformError`] 表达。
pub trait Platform {
    /// 平台标识（用于事件 `os_info`，如 `linux_x64`）。
    fn name(&self) -> &'static str;

    /// 能力声明。上层据此区分「探测失败」与「本就没有该能力」。
    fn capabilities(&self) -> Capabilities;

    /// 枚举系统进程。
    fn enumerate_processes(&self) -> Result<Vec<ProcessInfo>, PlatformError>;

    /// 在目标进程可读内存区中扫描字节特征，返回命中地址。
    fn scan_memory(&self, pid: u32, pattern: &[u8]) -> Result<Vec<MemoryHit>, PlatformError>;

    /// 采样最近 `window_ms` 毫秒内的输入事件。
    fn sample_input_events(&self, window_ms: u64) -> Result<Vec<InputSample>, PlatformError>;

    /// 反调试探针：探测本进程是否被调试/注入跟踪。
    fn anti_debug_probe(&self) -> Result<AntiDebugReport, PlatformError>;

    /// 按清单校验安装文件的大小与 CRC32。
    fn verify_install_integrity(
        &self,
        manifest: &IntegrityManifest,
    ) -> Result<IntegrityReport, PlatformError>;
}

/// 返回当前编译目标对应的平台实现。
pub fn current() -> Box<dyn Platform> {
    #[cfg(all(target_os = "linux", not(target_env = "ohos")))]
    let platform: Box<dyn Platform> = Box::new(linux::LinuxPlatform::new());
    #[cfg(target_os = "windows")]
    let platform: Box<dyn Platform> = Box::new(windows::WindowsPlatform::new());
    #[cfg(target_os = "android")]
    let platform: Box<dyn Platform> = Box::new(android::AndroidPlatform::new());
    #[cfg(target_os = "ios")]
    let platform: Box<dyn Platform> = Box::new(ios::IosPlatform::new());
    #[cfg(target_env = "ohos")]
    let platform: Box<dyn Platform> = Box::new(harmonyos::HarmonyPlatform::new());
    #[cfg(not(any(
        all(target_os = "linux", not(target_env = "ohos")),
        target_os = "windows",
        target_os = "android",
        target_os = "ios",
        target_env = "ohos"
    )))]
    let platform: Box<dyn Platform> = Box::new(UnsupportedPlatform::new(std::env::consts::OS));

    platform
}

/// 为无法真实落地的平台生成一份**诚实的最小实现**：能力全为 false，接口一律返回
/// [`PlatformError::Unsupported`]。这样上层能明确知道「无数据」而非「数据正常」。
macro_rules! unsupported_platform {
    ($name:ident, $os:expr, $reason:expr) => {
        pub struct $name;

        impl $name {
            pub fn new() -> Self {
                Self
            }
        }

        impl Default for $name {
            fn default() -> Self {
                Self
            }
        }

        impl $crate::Platform for $name {
            fn name(&self) -> &'static str {
                $os
            }

            fn capabilities(&self) -> $crate::Capabilities {
                $crate::Capabilities::default()
            }

            fn enumerate_processes(
                &self,
            ) -> Result<Vec<$crate::ProcessInfo>, $crate::PlatformError> {
                Err($crate::PlatformError::Unsupported($reason))
            }

            fn scan_memory(
                &self,
                _pid: u32,
                _pattern: &[u8],
            ) -> Result<Vec<$crate::MemoryHit>, $crate::PlatformError> {
                Err($crate::PlatformError::Unsupported($reason))
            }

            fn sample_input_events(
                &self,
                _window_ms: u64,
            ) -> Result<Vec<$crate::InputSample>, $crate::PlatformError> {
                Err($crate::PlatformError::Unsupported($reason))
            }

            fn anti_debug_probe(&self) -> Result<$crate::AntiDebugReport, $crate::PlatformError> {
                Err($crate::PlatformError::Unsupported($reason))
            }

            fn verify_install_integrity(
                &self,
                _manifest: &$crate::IntegrityManifest,
            ) -> Result<$crate::IntegrityReport, $crate::PlatformError> {
                Err($crate::PlatformError::Unsupported($reason))
            }
        }
    };
}

pub(crate) use unsupported_platform;

/// 未列入受支持目标时的兜底实现（同样只返回 Unsupported）。
pub struct UnsupportedPlatform {
    os: &'static str,
}

impl UnsupportedPlatform {
    pub fn new(os: &'static str) -> Self {
        Self { os }
    }
}

impl Platform for UnsupportedPlatform {
    fn name(&self) -> &'static str {
        self.os
    }

    fn capabilities(&self) -> Capabilities {
        Capabilities::default()
    }

    fn enumerate_processes(&self) -> Result<Vec<ProcessInfo>, PlatformError> {
        Err(PlatformError::Unsupported("无平台实现"))
    }

    fn scan_memory(&self, _pid: u32, _pattern: &[u8]) -> Result<Vec<MemoryHit>, PlatformError> {
        Err(PlatformError::Unsupported("无平台实现"))
    }

    fn sample_input_events(&self, _window_ms: u64) -> Result<Vec<InputSample>, PlatformError> {
        Err(PlatformError::Unsupported("无平台实现"))
    }

    fn anti_debug_probe(&self) -> Result<AntiDebugReport, PlatformError> {
        Err(PlatformError::Unsupported("无平台实现"))
    }

    fn verify_install_integrity(
        &self,
        _manifest: &IntegrityManifest,
    ) -> Result<IntegrityReport, PlatformError> {
        Err(PlatformError::Unsupported("无平台实现"))
    }
}
