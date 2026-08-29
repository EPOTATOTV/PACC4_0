# PACC HarmonyOS 客户端（ArkTS 反作弊探针）

玩家端检测层（HarmonyOS/OpenHarmony），本地采样加速 / hook 框架 / 越权特征。

## 结构
```
harmony/
  oh-package.json5        -> 模块声明
  probe.hvigor/probe.ts   -> ArkTS 探针（加速 / hook / root 检测）
```

## 检测能力
- **应用加速**：同一真实流逝窗口内对比墙钟与单调时钟的走时，偏差超过阈值判定加速（游戏提速）。
- **hook 框架**：遍历运行进程识别 frida / lsposed / riru / magisk / zygisk 特征。
- **越权路径**：`fileIo.accessSync` 校验 `su` / Magisk / Riru 路径。

## 使用
```typescript
import { PaccHarmonyProbe } from '../probe.hvigor/probe'

const probe = new PaccHarmonyProbe()
const result = await probe.scan() // 异步完整检测（含真实耗时采样，精确判定加速）
// 或 probe.quick() 做快速同步采样（不含加速判定）
```

事件折算：命中累加 0-100，`severity` 关联等级，交 `ptv-client` 统一上报 PTV。

## 构建（DevEco Studio / hvigor）
```bash
cd harmony
ohpm install
hvigorw assembleHap     # DevEco Studio 打开工程 Build -> Make Module
```
产出 `pacc_probe` 库/模块，嵌入宿主 App，游戏进程启动即装载。

> 说明：`probe.ts` 依赖 HarmonyOS SDK 提供的 `@kit.BasicServicesKit`（process）与
> `@kit.CoreFileKit`（fileIo）。这些模块的类型声明仅在 DevEco Studio / HarmonyOS SDK 环境中可用；
> 在本仓库的普通 TS 语言服务器中会报「找不到模块 @kit.*」，属「缺少 HarmonyOS SDK 类型」而非代码错误。
> 请在装有 DevEco Studio 与 HarmonyOS SDK 的环境中执行上述构建。