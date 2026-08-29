# PACC v4.0 平台模块

本目录承载「玩家端检测层」在不同操作系统 / 运行时的实现。
每个模块为可独立编译接入的标准化单元，通过统一的「端侧事件协议」
（见 `../proto/pacc.proto` 与玩家端 `../ptv-client`）向检测引擎上报。

| 目录 | 目标平台 / 载体 | 状态 |
| ---- | -------------- | ---- |
| `java-agent`   | Java 版游戏进程（-javaagent 注入） | ✅ 完整实现 |
| `kernel-windows` | Windows CDP 内核驱动（底层内存 / 进程 / 设备） | ✅ 完整实现 |
| `kernel-linux`   | Linux 可加载模块（同上层职责） | ✅ 完整实现 |
| `android`       | Android 原生服务（NDK 探针 / Frida-Xposed / root / 调试器） | ✅ 完整实现 |
| `ios`           | iOS 越狱 / dylib 注入 / 反调试检测 | ✅ 完整实现 |
| `harmony`       | HarmonyOS（Ark）加速 / hook 框架 / 越权检测 | ✅ 完整实现 |
| `linux-daemon`  | Linux 用户态守护（/proc 定位 + debugfs 上报） | ✅ 完整实现 |

各模块均为**纯玩家端本地采样**，将结果折算为统一 `DetectionEvent`（类型 / 严重度 / 0-100 预评分），
由玩家端用户态服务 `ptv-client` 决定上报策略，平台模块不直连 PTV（详见各子目录 README）。

设计原则：
1. 所有平台仅做本地采样，绝不接触游戏服务器数据。
2. 端侧结果统一折算为 `DetectionEvent`（类型 / 严重度 / 0-100 预评分）。
3. 由玩家端用户态服务 `ptv-client` 决定上报策略，平台模块不直连 PTV。