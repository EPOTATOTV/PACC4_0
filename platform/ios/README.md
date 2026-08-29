# PACC iOS/iPadOS 客户端（Swift 反作弊探针）

玩家端检测层（iOS），本地采样越狱 / dylib 注入 / 反调试特征。

## 结构
```
ios/
  Package.swift          -> SwiftPM 库定义
  Sources/PaccIosProbe.swift -> 越狱 / 注入 / 反调试检测
```

## 检测能力
- **越狱检测**：Cydia 等经典路径、沙盒逃逸写入、异常 `fork()`（越狱/非提权环境常见放行）、可疑动态库。
- **dylib 注入检测**：遍历 dyld image，识别 Frida / Cycript / Substrate / tweak 特征库。
- **反调试**：`sysctl(CTL_KERN_PROC)` 校验 `P_TRACED`。

事件折算：越狱=45、注入=45、调试=45，多命中累加 0-100，`severity` 关联等级，
经回调交 `ptv-client` 统一上报 PTV。

## 构建（macOS + Xcode）
```bash
cd ios
swift build                                                     # 或
xcodebuild -scheme PaccIosProbe -sdk iphoneos build
```
产物为静态库 `PaccIosProbe`，嵌入宿主 App，游戏进程启动即装载。

> 说明：本模块依赖 Darwin 系统调用（`fork` / `waitpid` / `sysctl` / `dyld_*`），
> 只能以 Swift 在 macOS/Xcode 上编译；Windows 主机无 Swift 工具链，无法直接构建。
> 请在装有 Xcode 的 macOS/iOS CI 上执行构建并做静态审查。