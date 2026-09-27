//! HarmonyOS（OpenHarmony/Ark）平台实现（设计文档 §4.2.1）。
//!
//! 需要 DevEco/ArkTS 原生层与 OHOS 系统能力（`@ohos.*`）才能枚举进程与检测越权，
//! 纯 std 构建不可得，因此如实声明不支持；真实采样由 `platform/harmony` 承担。

use crate::unsupported_platform;

unsupported_platform!(
    HarmonyPlatform,
    "harmonyos",
    "HarmonyOS 原生能力需 DevEco/OHOS 接口（见 platform/harmony）"
);
