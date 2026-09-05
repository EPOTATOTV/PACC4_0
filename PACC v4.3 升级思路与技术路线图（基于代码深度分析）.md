# PACC v4\.3 升级思路与技术路线图（基于代码深度分析）

> 部分内容由豆包生成
> 
> 

# 一、代码现状全面诊断

## 1\.1 仓库整体结构

截至 v4\.1，PACC 仓库包含 7 个核心模块，共 694 个文件。各模块状态如下：

|模块|文件数|技术栈|完成度|核心问题|
|---|---|---|---|---|
|pacc\-client|253（109 Java）|Java 21 \+ Netty \+ Protobuf \+ JNA|检测引擎 80%，GUI 0%|无 GUI、无红屏实现、无 PTEID 登录、无本地持久化、无 Java 版检测|
|pacc\-admin|12（6 页面）|Vue 3 \+ TS \+ Vite \+ Element Plus|30%|纯英文、无登录、无远程查端、Bans 页面架构冲突、数据硬编码|
|pacc\-server|51（25 Java）|Spring Boot 3\.2 \+ JPA \+ Redis \+ MySQL|50%|Ban 模块架构冲突、无远程查端 API、无审计日志、PTEID 注册字段不全|
|pacc\-driver|8（C 语言）|Windows 内核驱动（WDM）|40%|无键盘拦截、无 WHQL 签名、无安装脚本|
|pacc\-native|11（C\+\+）|JNI \+ 跨平台原生|50%|无输入钩子、无红屏覆盖、无进程注入|
|pacc\-plugin|17（6 Java）|Bukkit/Spigot 插件|40%|与"不获取服务器数据"架构需澄清、无配置、无权限|
|pacc\-proto|325（7 proto）|Protocol Buffers 3|70%|含 BanNotification（架构冲突）、无红屏/查端/IPC 协议|

## 1\.2 架构一致性问题（最高优先级）

代码中存在多处与 PACC 三条不可变更架构约束冲突的实现，必须在 v4\.3 优先修复：

|冲突点|位置|问题描述|修复方案|
|---|---|---|---|
|封禁逻辑残留|PaccConfig\.ThresholdConfig\.banThreshold=80|配置中定义了"封禁阈值"，与"无法封禁玩家"冲突|重命名为 redScreenThreshold，语义改为"红屏触发阈值"|
|封禁通知协议|detection\.proto BanNotification|Protobuf 定义了封禁通知消息|替换为 RedScreenNotification，字段改为红屏级别/查端状态/解锁时间|
|服务端封禁模块|BanController/BanService/BanRecord/BanRecordRepository|完整的封禁 CRUD 模块|整体改造为 RedScreenRecord 模块，记录红屏触发历史而非封禁|
|管理端 Bans 页面|pacc\-admin/src/views/Bans\.vue|封禁管理页面|改造为"红屏警告记录"页面，显示触发/查端/解锁全生命周期|
|Dashboard 统计项|Dashboard\.vue stats "Active Bans"|统计"活跃封禁数"|改为"活跃红屏数"和"待查端数"|

## 1\.3 安全问题

- **TLS 证书验证缺失**：NetworkAgent 使用 InsecureTrustManagerFactory\.INSTANCE，跳过证书校验，存在中间人攻击风险。必须替换为证书固定（Certificate Pinning）或系统信任库校验。

- **PTEID 注册无验证**：PteidController\.register 只接收 player\_name 和 device\_fingerprint，无手机号/邮箱验证码校验，可被批量注册。

- **管理端无认证**：所有 API 无 JWT 鉴权拦截（SecurityConfig 存在但需确认是否生效），管理端页面无登录页。

- **配置文件明文**：config\.yaml 中可能包含敏感信息，无加密保护。

- **本地数据无加密**：检测记录、PTEID 凭证本地存储无加密，重启保留机制未实现。

## 1\.4 功能缺失清单

|领域|缺失功能|影响|
|---|---|---|
|客户端 GUI|全部 7 个页面（登录/仪表盘/记录/设备/设置/红屏/诊断）|用户无法交互，纯后台服务|
|红屏警告|客户端红屏触发、全屏覆盖、键盘拦截、倒计时、管理员解锁|核心管控机制未实现|
|PTEID 体系|客户端登录/注册、短信/邮件验证码、设备绑定、凭证安全存储|账号体系不可用|
|远程查端|WebRTC 信令、屏幕共享、证据提取、查端记录|红屏后无法人工核查|
|Java 版检测|Java 版游戏进程识别、Java 版作弊特征库、Java 版行为分析|仅支持基岩版，无法进军 Java 端|
|本地持久化|检测记录本地加密存储、红屏状态重启保留、离线队列|数据易丢失，不满足重启保留要求|
|App 化|Tauri 桌面壳、Capacitor 移动壳、系统托盘、自动更新|无法作为独立 App 分发|
|管理端|登录认证、远程查端、PTEID 管理、审计日志、系统设置、管理员管理、中文化|管理功能不可用|

# 二、v4\.3 核心升级方向

## 2\.1 架构一致性修复（第 1 优先级）

### 2\.1\.1 封禁逻辑全面清除与替换

执行"Ban → RedScreen"全局重命名与语义替换：

1. **配置层**：PaccConfig\.ThresholdConfig 中 banThreshold → redScreenLevel2Threshold，新增 redScreenLevel1Threshold（环境异常警告）和 redScreenLevel3Threshold（确认作弊锁定）

2. **协议层**：detection\.proto 中 BanNotification → RedScreenNotification，字段改为：red\_screen\_id、pteid、level（LEVEL\_1/LEVEL\_2/LEVEL\_3）、trigger\_reason、trigger\_time、inspect\_status（PENDING/IN\_PROGRESS/UNLOCKED/CONFIRMED）、unlock\_time、operator\_id

3. **服务端**：BanController → RedScreenController（已有，需合并），BanService → RedScreenService（已有，需扩展），BanRecord → RedScreenEvent（已有），BanRecordRepository → RedScreenEventRepository

4. **管理端**：Bans\.vue → RedScreenRecords\.vue，路由 /bans → /redscreen，表格列改为：红屏ID、PTEID、级别、触发原因、触发时间、查端状态、操作员、解锁时间

5. **客户端**：DetectionResult 中 ban 相关字段 → redScreen 字段，报告器上报红屏事件而非封禁请求

### 2\.1\.2 三条架构约束代码级固化

在代码中通过架构约束（ArchUnit 测试）固化三条不可变更原则：

- **约束 1**：客户端代码中不得出现任何"封禁玩家"的 API 调用或逻辑，仅允许触发红屏警告和记录上报。ArchUnit 规则：noClasses\(\)\.should\(\)\.dependOnClassesThat\(\)\.resideInAPackage\("\.\.ban\.\."\)

- **约束 2**：服务端代码中不得出现访问游戏服务器数据库或 API 的代码。ArchUnit 规则：noClasses\(\)\.should\(\)\.dependOnClassesThat\(\)\.haveNameMatching\("\.\*GameServer\.\*"\)

- **约束 3**：管理面板部署配置独立于游戏服务器，application\.yml 中不得配置游戏服务器数据源

## 2\.2 客户端 GUI 与红屏完整实现（第 2 优先级）

### 2\.2\.1 GUI 技术栈与项目结构

在 pacc\-client 同级新增 pacc\-client\-gui 模块（Vue 3 \+ TypeScript \+ Vite \+ Element Plus），通过 Tauri 打包为桌面应用，与 Java 后端通过本地 WebSocket IPC 通信。

```text
pacc-client-gui/
├── src/
│   ├── pages/
│   │   ├── Login.vue          # PTEID 登录/注册
│   │   ├── Dashboard.vue      # 主界面仪表盘
│   │   ├── Detections.vue     # 检测记录
│   │   ├── Devices.vue        # 设备管理
│   │   ├── Settings.vue       # 设置
│   │   ├── RedScreen.vue      # 红屏警告（全屏）
│   │   └── Diagnostic.vue     # 诊断工具
│   ├── components/             # 共享组件
│   ├── composables/            # 组合式函数
│   ├── ipc/                    # WebSocket IPC 客户端
│   └── styles/                 # 全局样式
├── index.html
├── package.json
└── vite.config.ts
```

### 2\.2\.2 本地 WebSocket IPC 协议

Java 后端启动本地 WebSocket 服务（127\.0\.0\.1:随机端口），GUI 通过 WebSocket 连接。定义 20\+ 个 API：

|分类|API|方向|说明|
|---|---|---|---|
|认证|pteid\.login|GUI→后端|PTEID/手机号/邮箱 \+ 密码登录|
||pteid\.register|GUI→后端|三步注册（信息→验证码→完成）|
||pteid\.logout|GUI→后端|退出登录，清除本地凭证|
|状态|status\.get|GUI→后端|获取当前检测状态/资源占用/游戏进程|
||status\.start|GUI→后端|启动检测引擎|
||status\.stop|GUI→后端|暂停检测引擎|
||onStatusChange|后端→GUI|状态变更事件推送|
|记录|detections\.list|GUI→后端|分页查询本地检测记录|
||detections\.detail|GUI→后端|获取单条记录详情\+证据|
||detections\.export|GUI→后端|导出诊断报告|
|红屏|onRedScreen|后端→GUI|红屏触发事件（含级别/原因/倒计时）|
||redscreen\.confirm|GUI→后端|一级警告玩家点击"继续"|
||redscreen\.unlock|后端→GUI|管理员远程解锁通知|
|设备|devices\.list|GUI→后端|获取已绑定设备列表|
||devices\.unbind|GUI→后端|解绑指定设备|
|配置|config\.get|GUI→后端|获取当前配置|
||config\.update|GUI→后端|更新配置（持久化）|

### 2\.2\.3 红屏警告完整链路

红屏是 PACC 最核心的管控机制，v4\.3 必须实现端到端完整链路：

1. **触发层（Java 后端）**：DetectionEngine 检测到风险评分超过阈值时，调用 RedScreenManager\.trigger\(level, reason, evidence\)，生成本地红屏事件并持久化（AES\-256\-GCM 加密），同时通过 IPC 推送 onRedScreen 事件到 GUI

2. **显示层（GUI \+ Tauri）**：GUI 接收到 onRedScreen 后，Tauri 调用 set\_always\_on\_top\(true\) \+ set\_fullscreen\(true\) \+ set\_decorations\(false\)，创建全屏置顶窗口，红色渐变背景，显示警告信息。一级警告显示"我已知晓，继续游戏"按钮；二/三级警告无按钮，显示倒计时和"等待管理员远程查端"

3. **键盘拦截层（内核驱动）**：pacc\-driver 新增键盘过滤驱动，在红屏激活时通过 IoCallDriver 拦截键盘 IRP\_MJ\_READ，丢弃所有按键（保留 Ctrl\+Alt\+Del）。驱动与用户态通过 \\Device\\PaccDrv 通信，接收红屏激活/解除指令

4. **查端层（WebRTC）**：二/三级红屏触发后，客户端自动建立 WebRTC 连接到 PTV 信令服务器（api\.potatotv\.asia），等待管理员接入。管理员通过管理端远程查端页面发起屏幕共享请求，客户端 Tauri 调用屏幕捕获 API 共享屏幕

5. **解锁层**：管理员查端确认误报后，通过服务端 API 发送解锁指令，服务端通过 WebSocket 推送 redscreen\.unlock 到客户端，GUI 显示"查端完成，未发现作弊"绿色提示 3 秒后关闭红屏，驱动解除键盘拦截

6. **持久化层**：红屏事件、证据文件、查端记录全部本地加密存储（SQLite \+ SQLCipher），重启电脑后数据保留。客户端启动时检查是否有未解除的红屏，如有则自动恢复红屏状态

### 2\.2\.4 本地数据持久化

新增 pacc\-client 本地存储模块，使用 SQLite \+ SQLCipher 加密数据库：

- **detection\_records 表**：存储所有检测记录（ID、时间、类型、风险评分、证据哈希、是否已上报）

- **red\_screen\_events 表**：存储红屏事件（ID、PTEID、级别、原因、触发时间、查端状态、解锁时间、操作员）

- **pteid\_credentials 表**：存储 PTEID 登录凭证（PTEID、Token 哈希、刷新 Token、过期时间），使用系统密钥链加密

- **device\_bindings 表**：存储设备绑定信息（设备指纹、绑定时间、设备名称、是否当前设备）

- **offline\_queue 表**：存储离线时待上报的检测报告，网络恢复后批量上报

数据库密钥派生：使用设备指纹 \+ PTEID \+ 固定盐值通过 PBKDF2（200000 次迭代）派生 256 位密钥，确保不同设备/账号无法互相解密。

## 2\.3 PTEID 账号体系完善（第 3 优先级）

### 2\.3\.1 服务端 PTEID API 扩展

现有 PteidController 只有 3 个接口且字段不全，v4\.3 扩展为完整的账号体系：

|API|方法|路径|说明|
|---|---|---|---|
|发送验证码|POST|/api/v1/pteid/verify\-code/send|发送短信/邮件验证码，参数：type\(sms/email\)、target、scene\(register/login/reset\)|
|校验验证码|POST|/api/v1/pteid/verify\-code/verify|校验验证码，返回临时 token 用于注册/登录|
|注册|POST|/api/v1/pteid/register|完整注册：phone、email、qq\(选填\)、ecid\(自动\)、password、verify\_token、device\_fingerprint|
|密码登录|POST|/api/v1/pteid/login|PTEID/手机号/邮箱 \+ 密码登录，返回 access\_token \+ refresh\_token|
|验证码登录|POST|/api/v1/pteid/login\-code|手机号/邮箱 \+ 验证码登录|
|刷新 Token|POST|/api/v1/pteid/refresh|使用 refresh\_token 刷新 access\_token|
|退出登录|POST|/api/v1/pteid/logout|使当前 token 失效|
|获取账号信息|GET|/api/v1/pteid/me|获取当前登录账号信息（PTEID、手机号脱敏、邮箱脱敏、QQ、信誉分、注册时间）|
|修改密码|PUT|/api/v1/pteid/password|旧密码 \+ 新密码修改|
|重置密码|POST|/api/v1/pteid/password/reset|通过验证码重置密码|
|设备列表|GET|/api/v1/pteid/devices|获取已绑定设备列表（最多 5 台）|
|解绑设备|DELETE|/api/v1/pteid/devices/\{deviceId\}|解绑指定设备|
|信誉分查询|GET|/api/v1/pteid/reputation|获取当前账号信誉评分和等级|

### 2\.3\.2 PTEID 生成与存储规范

- **格式**：PT \+ 2 位时间戳编码（Base36 年份\+周数）\+ 8 位 CSPRNG 随机字符（Base36），共 12 位，示例 PTK7X9mQ2rLp

- **唯一性**：数据库唯一索引 \+ 生成时查重，冲突则重新生成

- **密码存储**：BCrypt（cost=12）哈希存储，禁止明文或 MD5/SHA1

- **手机号/邮箱**：数据库存储完整值，API 返回脱敏（138\*\*\*\*1234 / a\*\*\*@potatotv\.asia）

- **ECID**：客户端自动采集设备唯一标识（Windows: MachineGuid \+ 主板序列号哈希；Android: Android\_ID；iOS: IDFV），注册时自动上传

- **验证码**：6 位数字，5 分钟有效，同一目标 60 秒内只能发送 1 次，错误 5 次锁定 15 分钟，Redis 存储

### 2\.3\.3 短信/邮件服务集成

- **短信**：集成阿里云短信服务或腾讯云短信，签名"PACC"，模板："【PACC】您的验证码是 \{code\}，5 分钟内有效。如非本人操作请忽略。"

- **邮件**：集成 SMTP 服务（support@potatotv\.asia），HTML 模板，包含验证码和有效期说明

- **限流**：同一 IP 每分钟最多 10 次发送请求，同一手机号/邮箱每天最多 10 次

## 2\.4 管理端全面升级（第 4 优先级）

### 2\.4\.1 页面扩展与中文化

现有 6 个页面升级为 12 个页面，全部中文化：

|页面|路由|状态|核心功能|
|---|---|---|---|
|登录认证|/login|新增|管理员账号密码 \+ 2FA 双因素认证|
|仪表盘|/dashboard|升级|8\+ 统计指标、4\+ 图表、实时告警、待查端队列|
|检测记录|/detections|升级|多维度筛选、详情抽屉、证据查看、批量导出|
|红屏警告记录|/redscreen|改造（原 Bans）|红屏全生命周期、查端状态追踪、解锁记录|
|远程查端|/remote\-inspect|新增|待查端队列、WebRTC 屏幕共享、证据提取、查端记录|
|玩家管理|/players|升级|玩家列表、行为画像、信誉评分、设备关联|
|PTEID 账号管理|/pteid|新增|账号列表、账号详情、信誉调整、设备管理、状态管理|
|特征库管理|/signatures|升级|特征 CRUD、版本管理、灰度发布、命中率统计|
|策略配置|/policy|升级|检测灵敏度、模块开关、红屏策略、白名单管理|
|审计日志|/audit|新增|管理员操作日志、登录日志、配置变更、查端记录|
|管理员管理|/admins|新增|管理员账号、角色权限、2FA 管理|
|系统设置|/system|新增|服务器配置、通知设置、备份恢复、系统状态|

### 2\.4\.2 管理端技术架构升级

- **布局框架**：新增 AppLayout 组件（顶部导航栏 \+ 左侧菜单栏 \+ 主内容区 \+ 面包屑），替换当前无布局的裸页面

- **API 封装**：新增 src/api/ 目录，统一 axios 实例（baseURL: api\.potatotv\.asia），请求拦截器自动附加 JWT Token，响应拦截器统一处理 401 跳转登录、错误提示

- **状态管理**：启用 Pinia，新增 useAuthStore（登录状态/Token/管理员信息）、useAppStore（侧边栏折叠/主题/语言）

- **路由守卫**：router/index\.ts 添加全局前置守卫，未登录跳转 /login，权限不足显示 403

- **国际化**：集成 vue\-i18n，默认中文，预留英文支持

- **实时推送**：集成 WebSocket（socket\.io 或原生），接收红屏告警、查端请求、系统通知实时推送

### 2\.4\.3 远程查端 WebRTC 实现

远程查端是管理端最复杂的功能，v4\.3 实现完整链路：

1. **信令服务器**：服务端新增 WebRTCController，基于 WebSocket 实现信令交换（SDP offer/answer、ICE candidate）

2. **客户端**：Tauri 集成 libp2p\-webrtc 或使用系统 WebRTC API，红屏触发后自动创建 PeerConnection，等待管理员连接

3. **管理端**：远程查端页面使用 vue\-webrtc 或原生 RTCPeerConnection，点击"开始查端"后创建 offer，通过信令服务器交换，建立 P2P 屏幕共享

4. **屏幕捕获**：客户端 Tauri 调用 navigator\.mediaDevices\.getDisplayMedia 或 Windows Graphics Capture API，共享整个屏幕

5. **证据提取**：查端过程中管理员可截图（自动保存为证据）、录制视频（WebM 格式）、远程执行诊断命令（客户端白名单命令）

6. **查端记录**：每次查端自动记录：查端ID、PTEID、操作员、开始时间、结束时间、截图列表、视频URL、判定结果、备注

## 2\.5 Java 版检测扩展（第 5 优先级）

### 2\.5\.1 Java 版游戏进程识别

现有 BedrockCheatDetector 只识别基岩版进程，v4\.3 新增 Java 版检测模块：

- **进程识别**：新增 JavaGameDetector，通过进程名（java\.exe/javaw\.exe）\+ 命令行参数（net\.minecraft\.client\.main\.Main）识别 Java 版 Minecraft 进程，解析版本号（1\.8\.x / 1\.12\.x / 1\.16\.x / 1\.20\.x 等）

- **启动器识别**：识别官方启动器、HMCL、PCL2、MultiMC、Badlion Client、Lunar Client 等常见启动器和客户端

- **Mod 加载器识别**：通过进程模块列表识别 Forge、Fabric、Quilt 等 Mod 加载器，扫描 mods 目录获取已安装 Mod 列表

### 2\.5\.2 Java 版作弊检测

新增 JavaCheatDetector 模块，覆盖 Java 版常见作弊：

|检测类型|检测方法|目标作弊|
|---|---|---|
|内存特征扫描|扫描 Java 进程堆内存中的作弊类名、字段名、字符串常量|Wurst、Impact、Future、Sigma、Novoline 等已知作弊客户端|
|Mod 黑名单|扫描 mods 目录，匹配已知作弊 Mod 的 JAR 哈希和类名|作弊 Mod、X\-Ray 资源包、透明材质包|
|注入检测|检测 Java Agent 注入、JVMTI 附加、DLL 注入到 java\.exe|注入式作弊（如 Raven、LiquidBounce 注入版）|
|网络抓包|WinDivert 抓包分析游戏网络流量，检测异常数据包模式|数据包作弊（飞行、速度、无击退）|
|行为分析|复用现有 RageCheatDetector 和 StealthCheatDetector，适配 Java 版输入事件格式|KillAura、Reach、AutoClicker、Scaffold 等行为作弊|
|配置文件扫描|扫描 \.minecraft 目录下的作弊配置文件（\.json/\.txt）|作弊客户端配置、快捷键设置|

### 2\.5\.3 双端统一架构

重构 DetectionEngine，将基岩版和 Java 版检测统一为 GameDetector 接口：

```java
public interface GameDetector {
    GameType getGameType();           // BEDROCK / JAVA
    boolean isGameRunning();           // 检测游戏是否运行
    int getGamePid();                  // 获取游戏进程 PID
    String getGameVersion();           // 获取游戏版本
    List<DetectionResult> runFullScan();  // 全量扫描
    List<DetectionResult> runIncrementalScan();  // 增量扫描
}

// DetectionEngine 中同时持有 bedrockDetector 和 javaDetector
// 根据当前运行的游戏自动选择对应检测器
```

## 2\.6 App 化基础框架（第 6 优先级）

### 2\.6\.1 桌面端 Tauri 集成

新增 pacc\-client\-desktop 模块（Tauri \+ Rust），作为桌面 App 壳：

- **项目结构**：src\-tauri/（Rust 代码）\+ src/（引用 pacc\-client\-gui 的 Vue 页面）

- **Java 进程管理**：Rust 侧负责启动/停止/监控 Java 后端进程（pacc\-client\.jar），崩溃自动重启（最多 3 次），日志重定向到文件

- **系统托盘**：tauri\-plugin\-system\-tray，托盘菜单：显示主窗口 / 启动检测 / 暂停检测 / 检查更新 / 退出

- **全局快捷键**：tauri\-plugin\-global\-shortcut，Ctrl\+Shift\+P 显示/隐藏主窗口

- **开机自启**：tauri\-plugin\-autostart，安装时默认开启

- **自动更新**：tauri\-plugin\-updater，从 pacc\.potatotv\.asia/update 检查更新，支持增量更新

- **红屏全屏**：自定义 Rust 命令 redscreen\_show / redscreen\_hide，调用 Windows API SetWindowPos \+ SetForegroundWindow 实现最高优先级全屏覆盖

- **屏幕捕获**：自定义 Rust 命令 screen\_capture\_start / screen\_capture\_stop，用于远程查端屏幕共享

### 2\.6\.2 安装包构建

|平台|格式|说明|
|---|---|---|
|Windows|\.msi \+ \.exe|NSIS 安装包，包含 JRE 21 运行时、内核驱动（WHQL 签名）、VC\+\+ 运行库，支持静默安装 /S|
|macOS|\.dmg \+ \.pkg|应用公证（Notarization），包含 JRE，支持 Apple Silicon 和 Intel|
|Linux|\.AppImage \+ \.deb \+ \.rpm|包含 JRE，systemd 服务管理|

### 2\.6\.3 移动端 Capacitor 基础

v4\.3 先搭建移动端项目骨架（pacc\-client\-mobile），核心功能在 v4\.4 完善：

- **技术栈**：Capacitor 6 \+ Vue 3 \+ TypeScript \+ Vite \+ Element Plus（移动端适配）

- **平台**：Android（android/ 目录）\+ iOS（ios/ 目录），HarmonyOS 后续独立开发

- **核心功能**：PTEID 登录、检测状态查看、检测记录浏览、红屏推送接收、设备管理

- **原生插件**：@capacitor/push\-notifications（FCM/APNs 推送）、@capacitor\-community/biometrics（生物识别）、@capacitor/secure\-storage（安全存储）

- **定位**：移动端为"远程管理与查看工具"，不包含本地检测引擎（受系统限制）

## 2\.7 安全加固（第 7 优先级）

### 2\.7\.1 网络安全

- **TLS 证书固定**：NetworkAgent 中移除 InsecureTrustManagerFactory，替换为证书固定（Certificate Pinning），内置 api\.potatotv\.asia 的证书公钥哈希，防止中间人攻击

- **通信加密**：Protobuf 消息体在 TLS 基础上增加应用层 AES\-256\-GCM 加密，密钥通过 ECDHE 密钥交换协商

- **请求签名**：所有 API 请求附加 HMAC\-SHA256 签名（时间戳 \+ nonce \+ 请求体），防止重放攻击和篡改

- **WebSocket 认证**：本地 IPC WebSocket 连接时验证 Token，防止本地恶意程序连接

### 2\.7\.2 客户端防篡改

- **代码混淆**：pacc\-client 使用 ProGuard / R8 混淆，类名/方法名/字段名混淆为无意义名称，字符串加密

- **完整性校验**：SelfProtection 模块增强，启动时校验自身 JAR 文件 SHA\-256 哈希（与内置值比对），运行时定期校验代码段内存哈希，检测到篡改立即触发红屏

- **反调试增强**：AntiDebug 模块增加：Windows API 钩子检测（IsDebuggerPresent / CheckRemoteDebuggerPresent / NtQueryInformationProcess）、硬件断点检测、INT3 断点扫描、调试器窗口类名检测（x64dbg/OllyDbg/IDA）

- **反 Hook 增强**：HookDetector 增加：Inline Hook 检测（函数入口字节比对）、IAT Hook 检测（导入表地址比对）、SSDT Hook 检测（内核系统服务表）、驱动回调检测

- **驱动保护**：pacc\-driver 增加自身保护，防止被卸载/暂停/替换，驱动对象隐藏，注册表项保护

### 2\.7\.3 数据安全

- **本地加密**：所有本地数据（检测记录、红屏事件、PTEID 凭证）使用 SQLCipher 加密存储，密钥通过设备指纹 \+ PTEID 派生

- **内存保护**：敏感数据（密码、Token、密钥）在内存中使用后立即清零（Arrays\.fill），使用 ByteBuffer\.allocateDirect 避免 GC 移动导致的内存残留

- **证据完整性**：检测证据文件生成时计算 SHA\-256 哈希并签名，上传时校验哈希，防止证据被篡改

- **隐私合规**：遵循 PIPL/GDPR，最小化数据采集，提供数据导出和删除功能，敏感信息脱敏显示

## 2\.8 工程化与 CI/CD（第 8 优先级）

### 2\.8\.1 构建系统统一

- **Maven 多模块**：根 pom\.xml 增加 pacc\-client\-gui（frontend\-maven\-plugin 构建前端）、pacc\-client\-desktop（tauri\-maven\-plugin 构建桌面端）模块

- **版本统一**：所有模块版本号统一为 4\.3\.0，通过 Maven 属性管理

- **Profile 管理**：dev / test / prod 三个 Profile，分别配置不同的服务器地址、日志级别、优化选项

### 2\.8\.2 GitHub Actions CI/CD

新增 \.github/workflows/ 下的工作流：

|工作流|触发|任务|
|---|---|---|
|ci\.yml|PR / push to main|Java 编译 \+ 单元测试 \+ 代码质量（SonarQube）\+ 前端 lint \+ 构建验证|
|build\-desktop\.yml|tag v\*|构建 Windows/macOS/Linux 三平台安装包，上传到 Release|
|build\-mobile\.yml|tag v\*|构建 Android APK/AAB \+ iOS IPA（需 macOS runner）|
|deploy\-server\.yml|push to main|构建服务端 Docker 镜像，部署到 PTV 服务器|
|deploy\-admin\.yml|push to main|构建管理端静态文件，部署到 admin\.potatotv\.asia|

### 2\.8\.3 代码质量

- **代码规范**：Java 使用 Checkstyle（Google Java Style），前端使用 ESLint \+ Prettier，提交前自动格式化（Husky \+ lint\-staged）

- **静态分析**：Java 使用 SpotBugs \+ PMD，前端使用 ESLint 规则集，CI 中阻断严重问题

- **测试覆盖**：单元测试覆盖率目标 ≥ 60%（核心模块 ≥ 80%），使用 JaCoCo 统计，CI 中显示覆盖率报告

- **架构测试**：引入 ArchUnit，编写架构约束测试（三条不可变更原则、分层依赖规则、循环依赖检测）

- **依赖安全**：使用 OWASP Dependency\-Check 扫描依赖漏洞，CI 中告警

# 三、v4\.3 升级路线图

## 3\.1 分阶段实施计划

|阶段|时间|核心任务|交付物|
|---|---|---|---|
|Phase 1：架构修复|第 1\-2 周|Ban→RedScreen 全局替换、ArchUnit 架构约束、TLS 证书固定、PTEID 服务端 API 扩展|架构一致性修复完成、服务端 PTEID 完整 API|
|Phase 2：客户端核心|第 3\-6 周|pacc\-client\-gui 项目搭建、7 个页面实现、本地 WebSocket IPC、本地加密持久化、红屏触发逻辑|客户端 GUI 可运行版本、红屏触发与显示|
|Phase 3：红屏与查端|第 7\-10 周|内核驱动键盘拦截、Tauri 全屏覆盖、WebRTC 信令服务器、管理端远程查端页面、查端记录|红屏完整链路（触发→显示→拦截→查端→解锁）|
|Phase 4：管理端升级|第 11\-14 周|12 个页面中文化、登录认证\+2FA、布局框架、API 封装、审计日志、管理员管理、系统设置|管理端 v4\.3 完整版本|
|Phase 5：Java 版检测|第 15\-18 周|Java 版进程识别、Java 版作弊特征库、Java 版行为分析适配、双端统一架构重构|Java 版检测能力上线|
|Phase 6：App 化与发布|第 19\-22 周|Tauri 桌面壳集成、三平台安装包构建、移动端项目骨架、CI/CD 流水线、安全加固、测试与发布|PACC v4\.3 正式发布（桌面 App \+ 管理端 \+ 服务端）|

## 3\.2 优先级排序

**P0 必须完成（v4\.3 发布门槛）**：架构一致性修复、客户端 GUI 核心、红屏触发与显示、PTEID 登录注册、管理端登录认证\+中文化、TLS 安全加固

**P1 重要（v4\.3 核心价值）**：红屏键盘拦截、远程查端 WebRTC、本地加密持久化、管理端 12 页面、Java 版检测基础、Tauri 桌面 App

**P2 增强（v4\.3 体验提升）**：移动端骨架、自动更新、系统托盘、CI/CD 完整流水线、代码混淆增强、审计日志完善

## 3\.3 风险与应对

|风险|影响|应对措施|
|---|---|---|
|内核驱动 WHQL 签名周期长|Windows 平台无法正常加载驱动|提前申请微软硬件开发者账号，测试阶段使用测试签名，正式发布前完成 WHQL 认证|
|WebRTC 跨平台兼容性|远程查端在部分系统无法工作|优先使用 Tauri 内置 WebView2 的 WebRTC 能力，备选方案为 FFmpeg 屏幕录制\+推流|
|Java 版作弊特征更新快|新出现的作弊无法检测|建立特征库快速更新机制（云端热更新），AI 行为分析作为补充检测手段|
|红屏误报影响玩家体验|玩家投诉、信誉损失|三级红屏机制（一级可继续/二级待查端/三级锁定），误报快速申诉通道，管理员 24 小时响应|
|多模块并行开发集成冲突|集成阶段大量冲突|制定明确的接口契约（IPC API/REST API/Protobuf），每周集成测试，特性分支开发|

# 四、总结

PACC v4\.3 是从"检测引擎原型"到"可面世企业级产品"的关键版本。核心升级围绕四条主线：

1. **架构一致性**：彻底清除封禁逻辑，全面转向红屏警告架构，通过 ArchUnit 固化三条不可变更原则

2. **产品完整性**：补齐客户端 GUI、红屏完整链路、PTEID 账号体系、管理端 12 页面，形成可交付的完整产品

3. **平台扩展**：从基岩版扩展到 Java 版，从 Windows 扩展到全平台 App（Tauri 桌面 \+ Capacitor 移动）

4. **企业级质量**：安全加固（TLS/防篡改/数据加密）、工程化（CI/CD/代码质量/测试覆盖）、合规性（PIPL/GDPR）

建议按 6 个阶段、22 周周期实施，P0 功能在第 8 周前完成可内测版本，P1 功能在第 18 周完成可公测版本，v4\.3 正式版在第 22 周发布。

> （注：部分内容可能由 AI 生成）
