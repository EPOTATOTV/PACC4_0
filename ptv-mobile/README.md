# PACC 移动端 App（ptv-mobile）

基于 **Capacitor** 将 `ptv-frontend` 的 React 应用封装为 Android / iOS 原生应用。定位为**远程管理与查看工具**：

- 玩家：查看绑定设备的检测状态、历史记录、设备管理、PTEID 账号、接收红屏推送（FCM/APNs）、提交申诉。
- 管理员：待查端队列、远程查端发起、记录审核、实时告警。

> 与桌面端（`ptv-desktop`）不同：移动端**不内嵌本地检测引擎**（受移动系统限制），仅做远程查看与管理。HarmonyOS 需另建 DevEco 原生工程，不在本脚手架内。

## 目录
- `capacitor.config.ts`：`appId=asia.potatotv.pacc.mobile`，`webDir` 指向 `ptv-frontend/dist`
- `package.json`：Capacitor CLI/平台 + 推送/网络/生物识别/安全存储插件

## 构建步骤
```bash
# 1) 构建共享 Web 前端
npm run web:build
# 2) 同步 + 添加平台
npm run sync
npm run add:android
npm run add:ios        # 需 macOS + Xcode
# 3) 打开原生工程构建
npm run open:android
npm run open:ios
```

## 原生搭建要点
- **Android**：`google-services.json`（FCM）放入 `android/app/`；查看 `@capacitor/push-notifications` 说明。
- **iOS**：Xcode 配 APNs，App Transport Security 允许 `api.potatotv.asia`；生物识别需 `NSFaceIDUsageDescription`。
- **构建期镜像**：客观限制，Android 需 Android SDK、iOS 需 macOS+Xcode、HarmonyOS 需 DevEco，均需各自原生工具链机器，无法在当前 Windows 环境交叉编译。

## 接入向导（可复用 PlayerPortal）
移动端复用现有玩家门户路由（`/portal/*`）。装配原生插件需在 `PlayerPortal` 外层做一处 Platform 适配注入 `@capacitor/*`，页面本身无需改动。