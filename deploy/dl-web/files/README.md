# PACC 客户端安装包发布目录

将以下构建产物放入本目录后，即自动通过 dl.potatotv.asia 发布下载：

| 文件名 | 说明 |
|---|---|
| `pacc-client-windows-x64-v4.2.0.zip` | Windows 客户端安装包 |
| `pacc-client-linux-x86_64-v4.2.0.tar.gz` | Linux 客户端（含内核模块 + 守护进程） |
| `pacc-client-android-v4.2.0.apk` | Android 客户端 APK |
| `pacc-client-ios-v4.2.0.tar.gz` | iOS / iPadOS 客户端（Swift 源码包） |
| `pacc-client-harmony-v4.2.0.tar.gz` | HarmonyOS 客户端（ArkTS 源码包） |
| `ptv-agent-4.2.0.jar` | Java 版探针（JVM Agent） |

> 各平台客户端源码位于仓库 `platform/` 目录，需对应原生工具链（WDK、Linux 内核头、
> Android NDK、Xcode、DevEco Studio）交叉编译后发布，构建说明见各子目录 README。
