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

## 客户端加固（混淆）

`PaccManager.dll` 交付前做一次混淆，抬高逆向与改包成本。仓库里有两套互不干扰的机制，**默认只跑第一套**。

### 一、PCO（发行默认档，仓库自研）

- 位置：`tools/pco/`（C# / .NET 8，零第三方依赖，只用 .NET 自带的 `System.Reflection.Metadata`）；规则 `pco-rules.json`，靠 `build-client.ps1` 第 1b 步自动执行，不需要手动介入。
- 做什么：重命名类型名与成员名——就地改写元数据 `#Strings` 堆里的名字字节，不动任何下标，IL / BAML / 资源因此全部原样。`PaccManager.App` / `PaccManager.MainWindow` 与它们的全部成员整体保留（这两个类型承载 BAML：`x:Class`、事件处理器、`x:Name` 都是以**名字**写在里面的）。
- 无条件跳过：`.ctor` / `.cctor`、属性名、事件名、`get_` / `set_` / `add_` / `remove_` / `op_` 访问器、虚方法与接口实现、枚举成员（Literal）、P/Invoke 方法名与入口名、泛型参数名、编译器生成的怪名字（`<>c`、`<Foo>k__BackingField`）。
- 自测：`dotnet run --project tools/pco/tests`。CI 的 `pco` job 会跑同一套。
- 能力边界（有意为之，不是漏做）：不做字符串加密、不做控制流平坦化——这两项要往程序集里新增类型与方法，必须重写整张元数据表。另外 Roslyn 写元数据时会做**后缀共享**（`UniqueProcessId` 复用成 `InheritedFromUniqueProcessId` 的尾巴），这类名字改了会连带改坏别人，一律跳过，实测约两成名字因此改不动。所以**别按「能挡住 strings 搜索」预期 PCO**：字符串字面量仍是明文。
- 映射表留档到 `dist/pco-map/PaccManager-<version>-mapping.txt`（已在 `.gitignore`），绝不进 zip。
- Release 配置不产 PDB（`PaccManager.csproj`），堆栈里也不会有源码行号。

### 二、ConfuserEx（可选加强档）

- 配置：`ConfuserEx.crproj`；运行脚本：`confuserex-protect.ps1`（**不**在 `build-client.ps1` 与 CI 的默认路径里）。
- 做什么：在重命名之外叠加控制流（switch 分派）、常量动态还原、资源加密、反调试、反 dump、反 ildasm、反篡改。
- **为什么不是默认**：ConfuserEx 上游自 2019 年起基本停更，对 .NET 8 只做保守验证；反调试/反篡改/反 dump 会注入运行时自检代码，**与杀软 / EDR 的启发式扫描存在真实误报代价**——可能被拦截、拖慢启动，个别受管环境里甚至假崩溃。所以它只在明确接受这些代价、且做过真实终端回归时才启用。
- 用法（先把 publish 产出来，再就地加固）：

  ```powershell
  dotnet publish PaccManager.csproj -c Release -r win-x64 --self-contained true -p:PublishSingleFile=false -o dist/win-x64
  powershell -ExecutionPolicy Bypass -File tools/windows-gui/confuserex-protect.ps1
  ```

  离线/受控环境用 `-ConfuserPath` 指定自备的 `Confuser.CLI.exe`。脚本会把加固后的 dll 覆盖回 `dist/win-x64`，`symbols.map` 留档到 `dist/confuserex-map/`，并断言发布目录里没有残留映射表。

### 两套的关系与取舍

- 二选一优先：两套都做重命名，串起来跑（先 ConfuserEx 再 PCO）属于二次处理，收益有限、出问题的面更大。要更强就用 ConfuserEx 替代 PCO，而不是叠加。
- 无论走哪套，映射表（`Mapping.txt` / `symbols.map` / PCO 的 `pco-map`）都只留档不发布——随包外泄等于把符号表直接送给逆向者。
- 混淆只提高成本，不提供保密：默认档 PCO 连字符串都还是明文，ConfuserEx 的字符串加密与常量还原也只是提高门槛，能被有决心的分析者还原，别把它当机密性保证。