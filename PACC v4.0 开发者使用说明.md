# PACC v4\.0 开发者使用说明

本文档面向 PACC（Professional Anti\-Cheat Client）v4\.0 的开发者、集成工程师与第三方服务器管理员，详细说明反作弊客户端的安装配置、API 集成、特征库管理、远程查端操作与故障排查。PACC v4\.0 支持 Minecraft 基岩版与 Java 版双端统一检测，采用纯玩家端检测 \+ PTV 管控 \+ 红屏强制警告架构。

# 系统概述

## 架构简介

PACC v4\.0 由三层组成：

1. 玩家端检测层：安装于玩家设备，通过内核驱动 \+ JVM 探针采集本地全维度数据

2. 双端适配层：统一基岩版与 Java 版的检测数据格式与通信协议

3. PTV 管控层：部署于 PTV 服务器，负责 AI 分析、红屏广播、PTEID 账号管理、远程查端

PACC 完全无法封禁玩家，仅能红屏警告 \+ 永久记录 \+ 管理员远程查端。系统完全无法获取游戏服务器数据，所有数据仅来自玩家本地采集。

## 核心能力

- 内存篡改检测（驱动级扫描 \+ JVM 字节码校验）

- 进程/模块监控（内核回调 \+ 注入检测）

- 输入设备监控（IRP 拦截 \+ 点击时序分析）

- USB 设备检测（设备 DNA 画像 \+ HID 描述符分析）

- 游戏行为检测（移动/战斗/交互数据采集 \+ AI 分析）

- 基岩版外挂检测（CE 专项 \+ 作弊客户端特征库）

- Java 版外挂检测（JVM 探针 \+ Forge/Fabric 模组检测 \+ 作弊客户端特征库）

- 红屏强制警告（全屏高优先级覆盖 \+ 键盘强制暂停 \+ 全在线广播）

- PTEID 独立账号体系（手机号 \+ 邮箱 \+ QQ \+ ECID \+ 12 位 PTEID）

- 管理员远程查端（WebRTC 屏幕查看 \+ 证据远程提取 \+ 远程解锁）

- 本地加密持久化（AES\-256 \+ SHA\-256 哈希链，重启后保留）

# 系统要求

## Windows 客户端

|项目|要求|
|---|---|
|操作系统|Windows 10/11 64 位（推荐 Windows 11 22H2\+）|
|CPU|x86\-64 架构，支持 SSE 4\.2|
|内存|最低 4GB，推荐 8GB 以上（反作弊自身占用 \< 250MB）|
|磁盘空间|最低 500MB 可用空间|
|网络|稳定的互联网连接，需访问 PTV 管控服务器|
|权限|管理员权限（安装驱动与服务需要）|
|安全软件|建议将 PACC 安装目录加入杀毒软件白名单|
|Java 版支持|Java 8 \~ Java 21（游戏 JVM 版本），PACC 探针自动适配|

## 其他平台

|平台|系统版本|说明|
|---|---|---|
|Android|Android 8\.0\+|Root/非Root双模式，支持 ARM64\-v8a|
|iOS|iOS 14\.0\+|越狱/非越狱双模式，非越狱通过 TestFlight 分发|
|iPadOS|iPadOS 14\.0\+|与 iOS 共享代码库，平板适配|
|HarmonyOS|HarmonyOS 3\.0\+|原生 ArkTS 适配，支持原子化服务|
|Linux|Ubuntu 20\.04\+/Debian 11\+/Arch|仅 Java 版支持，\.deb/\.rpm/\.tar\.gz 包格式|

# 安装指南

## Windows 客户端安装

### 标准安装

1. 从 PTV 官方渠道下载 PACC 安装包（PACC\-Setup\-v4\.x\.x\.exe）

2. 右键以管理员身份运行安装包

3. 阅读并同意《PACC 玩家协议》与《隐私政策》

4. 选择安装目录（默认 C:\\Program Files\\PACC）

5. 等待安装完成，安装程序会自动：安装内核驱动 PaccDriver\.sys、注册 PACC Service 系统服务、创建桌面快捷方式、配置开机自启

6. 安装完成后启动 PACC 客户端

7. 使用 PTEID 账号登录（如无账号请先注册）

8. 登录成功后 PACC 自动连接 PTV 服务器，同步特征库，开始检测

### 静默安装（批量部署）

适用于网吧、电竞馆等批量部署场景：

```bash
PACC-Setup-v4.x.x.exe /VERYSILENT /SUPPRESSMSGBOXES /NORESTART /LOG="C:\pacc_install.log"
```

### 绿色版（单文件）

使用 Enigma Virtual Box 打包的单文件绿色版，无需安装：

1. 下载 PACC\-Portable\-v4\.x\.x\.exe

2. 以管理员身份运行

3. 首次运行会自动安装驱动并注册临时服务

4. 关闭程序后自动清理临时文件（驱动保留）

## Android 客户端安装

1. 下载 PACC APK 安装包

2. 在设置中允许安装未知来源应用

3. 安装 APK 并启动

4. 授予无障碍服务权限（非Root模式必需）

5. Root 设备授予 Root 权限以获得完整检测能力

6. 使用 PTEID 登录

## iOS / iPadOS 客户端安装

**非越狱设备：**

1. 通过 TestFlight 邀请链接安装 PACC

2. 启动应用并授予必要权限

3. 使用 PTEID 登录

**越狱设备：**

1. 添加 PACC Cydia/Sileo 源

2. 搜索并安装 PACC 插件

3. 重启 SpringBoard

4. 在设置中配置 PACC 并登录 PTEID

## HarmonyOS 客户端安装

1. 在鸿蒙应用市场搜索 "PACC" 并安装

2. 或通过原子化服务免安装调用

3. 启动后授予必要权限并登录 PTEID

## Linux 客户端安装（Java 版）

```bash
# Debian/Ubuntu
sudo dpkg -i pacc_4.0.0_amd64.deb
sudo systemctl enable --now pacc

# RPM 系
sudo rpm -ivh pacc-4.0.0-1.x86_64.rpm

# 通用 tar.gz
tar xzf pacc-4.0.0-linux-x64.tar.gz
cd pacc-4.0.0
sudo ./install.sh
```

# PTEID 账号管理

## 注册 PTEID 账号

1. 在 PACC 客户端登录界面点击"注册 PTEID 账号"

2. 输入手机号，点击"获取验证码"，输入收到的短信验证码

3. 输入邮箱地址，点击"发送验证邮件"，点击邮件中的验证链接

4. （可选）绑定 QQ 号

5. 设置密码（8\-32 位，需包含大小写字母 \+ 数字）

6. 阅读并同意《PACC 玩家协议》与《隐私政策》

7. 点击"注册"，系统自动生成 12 位 PTEID

8. 保存 PTEID（用于登录和找回账号）

PTEID 格式示例：PTK7X9mQ2rLp。前 2 位为固定前缀 "PT"，第 3\-4 位为时间戳编码，后 8 位为随机字符。PTEID 一旦生成不可修改。

## 登录

- 支持 PTEID \+ 密码登录

- 支持手机号 \+ 密码登录

- 支持邮箱 \+ 密码登录

- 支持 QQ 授权登录

- 新设备登录需短信/邮箱二次验证

## 密码找回

1. 在登录界面点击"忘记密码"

2. 输入注册时的手机号或邮箱

3. 获取验证码并验证

4. 设置新密码

5. 使用新密码登录

# 配置指南

## 客户端配置文件

Windows 客户端配置文件位于：%ProgramData%\\PACC\\config\\pacc\.toml

```toml
[general]
log_level = "info"           # debug/info/warn/error
auto_start = true             # 开机自启
auto_update = true            # 自动更新

[connection]
ptv_server = "wss://api.potatotv.asia/ws"
api_server = "https://api.potatotv.asia/api"
timeout = 30                  # 连接超时（秒）
retry_interval = 5            # 重连间隔（秒）

[detection]
memory_scan_enabled = true
process_monitor_enabled = true
input_monitor_enabled = true
usb_monitor_enabled = true
game_edition = "auto"         # auto/bedrock/java
sensitivity = "normal"        # low/normal/high

[redscreen]
keyboard_lock_enabled = true  # 红屏时强制暂停键盘
broadcast_enabled = true      # 接收全在线广播
overlay_priority = "highest"  # 窗口优先级

[storage]
local_encryption = true       # 本地加密持久化
max_local_records = 100       # 本地最多保留记录数
upload_on_wifi_only = false   # 仅 WiFi 下上传证据
```

## 检测灵敏度配置

|灵敏度|检测频率|适用场景|
|---|---|---|
|low|全量扫描 10 分钟，增量 60 秒|低配置设备，对性能敏感|
|normal|全量扫描 5 分钟，增量 30 秒|默认配置，平衡性能与检测率|
|high|全量扫描 2 分钟，增量 10 秒|高风险账号，赛事/竞技场景|

# PTV 管理后台使用

## 登录管理后台

1. 访问 PTV 管理后台地址（由 PTV 管理员提供）

2. 使用管理员账号登录

3. 首次登录需配置二次验证（TOTP）

## 数据大盘

登录后默认进入数据大盘，展示：

- 实时在线 PTEID 数量

- 今日检测次数 / 红屏警告次数

- 作弊类型分布饼图

- 近 7 天红屏警告趋势图

- 基岩版 / Java 版玩家分布

- 广播送达率

- 系统健康状态

## 远程查端操作

### 发起远程查端

1. 在"红屏警告管理"中找到待查端的记录

2. 点击"远程查端"按钮

3. 系统建立 WebRTC 连接，实时显示玩家屏幕

4. 左侧面板显示：进程列表、已加载模组（Java 版）、内存使用、网络连接

5. 右侧面板显示：检测证据、行为数据回放、风险评分详情

### 查端结论与解锁

|结论|操作|说明|
|---|---|---|
|确认作弊|提交结论 \+ 选择解锁/维持|记录永久保留，可选择解锁（玩家承诺不再作弊）或维持锁定（需客服申诉）|
|误报|提交结论 \+ 立即解锁|删除误报记录，远程解锁，系统自动发送致歉通知|
|可疑待查|延长锁定 \+ 提交高级分析|延长查端时间，提取更多证据，转交高级安全分析师|

## 特征库管理

1. 进入"特征库管理"页面

2. 点击"新增特征码"，输入特征码（支持通配符 ??）、名称、风险等级、适用版本（基岩/Java/通用）

3. 保存后特征码进入"待发布"状态

4. 选择灰度发布比例（1% → 10% → 50% → 100%）

5. 监控误报率，确认无误后全量发布

6. 出现问题可一键回滚到上一版本

## Java 版模组白名单管理

1. 进入"特征库管理" → "模组白名单"

2. 点击"添加模组"，输入模组 ID、名称、版本范围、SHA\-256 哈希

3. 白名单内的模组不会触发作弊检测

4. 未知模组默认标记为"可疑"，上报 PTV 进行威胁分析

# 开放 API

## 认证方式

所有 API 请求需在 Header 中携带 API Key：

```http
Authorization: Bearer YOUR_API_KEY
Content-Type: application/json
```

## 账号查询 API

查询 PTEID 账号的风险评分、红屏记录与历史作弊记录：

```http
GET /api/v1/account/{pteid}/profile
```

响应示例：

```json
{
  "pteid": "PTK7X9mQ2rLp",
  "reputation_score": 85,
  "account_status": "normal",
  "total_redscreen_count": 2,
  "last_redscreen_time": "2026-08-15T10:30:00Z",
  "game_edition": "java",
  "registered_at": "2026-01-01T00:00:00Z"
}
```

## 检测数据上报 API

```http
POST /api/v1/detection/report
```

```json
{
  "pteid": "PTK7X9mQ2rLp",
  "event_type": "memory_tamper",
  "severity": "high",
  "game_edition": "bedrock",
  "timestamp": "2026-08-27T10:00:00Z",
  "evidence": {
    "process_name": "cheatengine-x86_64.exe",
    "memory_region": "0x7FF600000000",
    "signature_hit": "CE_INJECTION_SIG_001"
  },
  "client_version": "4.0.0",
  "os_info": "Windows 11 Pro 23H2"
}
```

## 红屏警告 Webhook

配置 Webhook 后，红屏警告事件会实时推送到指定 URL：

```json
{
  "event_type": "redscreen_alert",
  "alert_id": "alert_20260827_001",
  "level": 2,
  "cheat_type": "auto_clicker",
  "pteid_masked": "PTK7***Lp",
  "timestamp": "2026-08-27T10:00:00Z",
  "risk_score": 88,
  "game_edition": "java",
  "online_players_broadcast": 1250,
  "admin_action_required": false
}
```

## 统计数据 API

```http
GET /api/v1/stats/summary?start_date=2026-08-01&end_date=2026-08-27
GET /api/v1/stats/cheat-types?period=7d
GET /api/v1/stats/redscreen-trend?granularity=daily
```

# 故障排查

## 常见问题

|问题|可能原因|解决方案|
|---|---|---|
|驱动安装失败|权限不足 / 安全软件拦截|以管理员身份运行安装程序，将 PACC 加入杀毒软件白名单|
|无法连接 PTV 服务器|网络问题 / 防火墙拦截|检查网络连接，确认防火墙允许 PACC 访问 443 端口，尝试切换网络|
|红屏不显示|全屏游戏独占模式 / 显卡驱动问题|更新显卡驱动，在游戏设置中关闭"全屏独占"模式，使用无边框窗口模式|
|键盘锁定无法解除|网络断开导致无法接收解锁指令|恢复网络连接，PACC 会自动同步锁定状态；如持续锁定请联系客服|
|游戏崩溃|与其他模组/客户端冲突|检查冲突软件列表，更新 PACC 到最新版本，提交崩溃日志|
|CPU 占用过高|高灵敏度配置 / 低配置设备|在配置中将灵敏度改为 low，关闭不必要的检测模块|
|Java 版探针未加载|JVM 参数未配置 / 启动器不支持|确认启动器 JVM 参数中包含 \-javaagent 参数，使用支持的启动器列表|
|PTEID 登录失败|密码错误 / 账号锁定 / 网络问题|确认密码正确，连续 5 次错误会锁定 30 分钟，检查网络连接|

## 日志收集

客户端日志位于：%ProgramData%\\PACC\\logs\\

- pacc\-service\.log：用户态服务日志

- pacc\-driver\.log：内核驱动日志（需开启调试模式）

- pacc\-jvm\-agent\.log：Java 版探针日志

- detection\-events\.log：检测事件日志

开启调试日志：在配置文件中设置 log\_level = "debug"，重启 PACC 服务。

## 联系技术支持

- 邮箱：support@potatotv\.asia

- 企业内部：联系 PTV 综合部

- 提交 Bug：在 PTV 管理后台"反馈"页面提交

# 附录

## 支持的启动器列表

|启动器|版本|说明|
|---|---|---|
|Minecraft 官方启动器|全版本|自动检测，无需额外配置|
|Prism Launcher|最新版|自动注入 Java Agent|
|MultiMC|0\.6\.16\+|需在实例设置中配置 JVM 参数|
|HMCL|3\.5\+|自动检测，支持版本隔离|
|PCL2|2\.8\+|自动检测，支持版本隔离|
|Badlion Client|最新版|共存检测，PACC 与 Badlion 反作弊独立运行|
|Lunar Client|最新版|共存检测，PACC 与 Lunar 反作弊独立运行|

## 版本号说明

PACC 采用语义化版本号：主版本\.次版本\.修订版本

- 主版本：架构级变更（如 v3\.0 → v4\.0 新增 Java 版支持）

- 次版本：新增功能，向下兼容（如 v4\.1 新增远程查端）

- 修订版本：Bug 修复与特征库更新（如 v4\.0\.1）

## 文档版本

本文档对应 PACC v4\.0，最后更新日期：2026\-08\-27

> （注：部分内容可能由 AI 生成）
