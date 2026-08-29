# PACC Android 客户端（NDK 反作弊探针）

玩家端检测层（Android），本地采样注入 / root / 调试器 / 环境篡改等特征。

## 结构
```
android/
  app/
    src/main/
      cpp/
        CMakeLists.txt   -> NDK 构建
        native_probe.cpp -> 原生探针（maps 扫描 / root / TracerPid / ROM 校验）
      java/com/potatotv/pacc/android/NativeProbe.java -> JNI 桥 + 事件折算
    build.gradle.kts    -> Android build 配置（AGP / NDK）
```

## 检测能力
- **注入库检测**：扫描 `/proc/self/maps` 识别 Frida / Substrate / Xposed / 加固魔改 lib。
- **root 检测**：检查 `su` / Magisk / Riru 路径与挂载标记。
- **调试器检测**：读取 `/proc/self/status` 的 `TracerPid`。
- **SIM/环境篡改**：解析 `/system/build.prop`（ro.secure / ro.debuggable / ro.build.tags）与 goldfish/ranchu 模拟器特征。
- **库劫持 / 系统篡改**：`LD_PRELOAD` + rwx 可写可执行内存段扫描、`ptrace` 反调试自检（TRACEME/DETACH）。

所有结果经 JNI 折算为 `DetectionEvent`（score 0-100 / severity），由 `ptv-client` 统一上报 PTV，不直连网络。

## 构建（需 Android SDK + NDK 26+）
```bash
# 项目根启用原生构建（build.gradle.kts 已配置 externalNativeBuild）
./gradlew assembleDebug
# 或在 IDE 打开本模块，Build -> Make Project
```

产物：`libpacc_native_probe.so`（多 ABI），由 Java 层 `System.loadLibrary` 加载。

> 说明：`native_probe.cpp` 依赖 Android NDK 头文件与 sysroot（`jni.h`、`android/log.h`、`sys/ptrace.h`）。
> 在未安装 NDK 的桌面主机（如本仓库开发机为 Windows）上，clang 语言服务器会报 `jni.h not found` / 系统头缺失等，
> 属「缺少 NDK 编译链」而非代码错误；请在装有 NDK 26+ 的 CI/构建机执行上述构建并以 `arm64-v8a`/`x86_64` 产物为准。