# PACC Windows 管理工具（C# / .NET 8 WPF）

按环境清单「Windows 工具 C#」交付：**安装程序 GUI + 配置管理工具 + 诊断工具**，统一为单个 WPF 桌面应用。

## 功能

| 页签 | 能力 |
|------|------|
| 安装 | 环境自检（管理员权限 / 架构 / 网卡 / 域名连通性）、一键安装/修复、生成默认配置 |
| 配置 | 读写 `pacc-client.properties`（域名绑定、红屏阈值、采样率、日志级别、PTEID），在线测温 |
| 诊断 | 本机网络基线、关键端点测温报告、一键复制报告 |

## 技术栈

- C# / .NET 8.0 LTS（WPF），`<OutputType>WinExe</OutputType>`，`TargetFramework=net8.0-windows`
- `app.manifest` 声明 `requireAdministrator`（访问底层检测所需）
- 校验/接口采用配额式区间校验、异步 HTTP 测温，无第三方依赖

## 构建与运行

```powershell
dotnet build tools/windows-gui/PaccManager.csproj -c Release
# 或直接运行（需要 .NET 8 Desktop SDK）
dotnet run --project tools/windows-gui/PaccManager.csproj
```

> 本机未安装 .NET SDK，故以标准工程源码交付，编译即用，无外部运行时依赖之外的约束。

## 安装说明

GUI 的「安装」页会调用 `deploy/installer.ps1` 完成真实部署（管理员校验、目录创建、配置基线、服务注册占位）。

> Windows 驱动模块需 WHQL 签名（见 `platform/kernel-windows/`），本工具仅负责配套的安装编排与配置管理。