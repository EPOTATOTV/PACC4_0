//! Windows 平台实现（设计文档 §4.2.1）。
//!
//! 真实落地需要 WinAPI（`NtQuerySystemInformation` / `ReadProcessMemory` /
//! `NtQueryInformationProcess` / `WinVerifyTrust`），而本项目坚持零第三方依赖
//! （不引入 `windows` crate）。因此在纯 std 约束下本实现**如实声明不支持**：
//! 能力位全为 false，所有接口返回 [`crate::PlatformError::Unsupported`]，
//! 由 `pacc-core` 的上层调用方决定回退策略（例如改用 C 内核驱动 `kernel-windows`）。
//!
//! 这不是「未实现」，而是「在有 WinAPI 之前不谎报数据」。

use crate::unsupported_platform;

unsupported_platform!(
    WindowsPlatform,
    "windows",
    "纯 std 构建下 Windows 原生能力需 WinAPI（见 platform/kernel-windows）"
);
