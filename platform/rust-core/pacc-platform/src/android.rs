//! Android 平台实现（设计文档 §4.2.1）。
//!
//! Android 的进程枚举 / 内存读取 / 输入采样需要 NDK 与 JNI（`/proc` 访问受
//! SELinux 限制，root/`adb` 权限不同结论不同）。纯 std 无法给出可信结论，
//! 因此本实现如实声明不支持；移动端的真实采样由 `platform/android`（NDK 探针）承担。

use crate::unsupported_platform;

unsupported_platform!(
    AndroidPlatform,
    "android",
    "Android 原生能力需 NDK/JNI（见 platform/android）"
);
