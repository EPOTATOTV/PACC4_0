# PACC 客户端安装包发布目录

将以下构建产物放入本目录后，即自动通过 `dl.your-domain.com` 发布下载：

| 文件名 | 说明 |
|---|---|
| `pacc-client-windows-x64-v5.0.0.zip` | Windows 客户端安装包 |
| `pacc-client-linux-x86_64-v5.0.0.tar.gz` | Linux 客户端（含内核模块 + 守护进程） |
| `pacc-client-android-v5.0.0.apk` | Android 客户端 APK |
| `pacc-client-ios-v5.0.0.tar.gz` | iOS / iPadOS 客户端（Swift 源码包） |
| `pacc-client-harmony-v5.0.0.tar.gz` | HarmonyOS 客户端（ArkTS 源码包） |
| `ptv-agent-5.0.0.jar` | Java 版探针（JVM Agent） |

> 各平台客户端源码位于仓库 `platform/` 目录，需对应原生工具链（WDK、Linux 内核头、
> Android NDK、Xcode、DevEco Studio）交叉编译后发布，构建说明见各子目录 README。

## 校验和怎么来

产物放进来后跑一次脚本，算出真实的 SHA-256 与体积：

```bash
./deploy/dl-web/compute-sha256.sh -o releases.json
```

它按文件名判断 platform / artifact，输出 JSON（含 `size_bytes` 与 `sha256`）。拿到数值后
追加 Flyway 迁移、或由管控台写入 `t_dl_release`，前端 `/api/dl/latest` 会自动带出。
**不要手填校验和**——填错的校验和比没有校验和更糟。
