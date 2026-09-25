//! iOS 平台实现（设计文档 §4.2.1）。
//!
//! iOS 沙箱不允许任意进程枚举与跨进程内存读取，反调试与越狱检测需调用
//! `sysctl`/`ptrace` 等 Darwin 专有接口。纯 std 构建下无法提供，
//! 因此如实声明不支持；真实采样由 `platform/ios`（dylib 注入检测）承担。

use crate::unsupported_platform;

unsupported_platform!(
    IosPlatform,
    "ios",
    "iOS 原生能力需 Darwin 系统接口（见 platform/ios）"
);
