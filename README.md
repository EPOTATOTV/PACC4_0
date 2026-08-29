# PACC v4.0 — 企业级游戏反作弊系统（双端统一架构）

> **PACC（Professional Anti-Cheat Client）v4.0**：面向 Minecraft 基岩版与 Java 版双端统一的企业级纯玩家端反作弊系统。
>
> 采用 **纯玩家端检测 + PTV 管控 + 红屏强制警告 + 管理员远程查端** 架构。系统**不具备封禁玩家能力**，仅通过红屏警告、强制暂停键盘输入、全在线广播、永久记录与管理员远程查端进行威慑与取证。

## 目录结构

```
d:\pacc\
├── PACC v4.0 企业级游戏反作弊系统技术设计文档...md   # 技术设计文档
├── PACC v4.0 开发者使用说明.md                        # 开发者使用说明
├── PACC 纯玩家端反作弊系统 v3.0 技术设计文档...md      # v3.0 技术设计文档（历史基线）
│
├── proto/                  # 玩家端 ↔ PTV 统一通信协议（Protocol Buffers）
├── ptv-backend/            # PTV 管控层（Java 21 + Spring Boot 3 微服务）
├── ptv-frontend/           # PTV 管理后台前端（TypeScript + React + Vite）
├── ptv-client/             # 玩家端用户态服务（Java 21 LTS）
├── platform/               # 平台特定模块（内核驱动/Java Agent/移动端/Linux）
│   ├── java-agent/         #   Java 版探针（字节码扫描）
│   ├── kernel-windows/     #   Windows 内核驱动（CDP）
│   ├── kernel-linux/       #   Linux 内核模块（Kprobe）
│   ├── linux-daemon/       #   Linux 用户态守护进程
│   ├── android/            #   Android 客户端（NDK）
│   ├── ios/                #   iOS / iPadOS 客户端（Swift）
│   └── harmony/            #   HarmonyOS 客户端（ArkTS）
├── deploy/                 # 部署编排
│   ├── docker/             #   容器构建公共配置（Maven 镜像设置）
│   ├── gateway/            #   域名网关（admin/api/pacc/dl 四子域名 + TLS）
│   ├── gateway-go/         #   Go 边缘网关（限流 + 鉴权 + Prometheus 指标）
│   ├── dl-web/             #   PACC 客户端下载站静态资源
│   ├── ai/                 #   AI 推理服务（FastAPI + NumPy，规则加权 + 统计异常）
│   ├── pipe-rust/          #   Rust 数据管道（SHA-256 指纹 + HMAC 签名 + 窗口聚合 + AES 加密）
│   ├── monitoring/         #   监控栈（Prometheus + Grafana + 告警规则）
│   ├── helm/               #   Kubernetes Helm Chart（后端/前端/MySQL/Ingress）
│   └── k8s/                #   Kubernetes 编排（可选）
├── tools/
│   └── windows-gui/        # Windows 管理工具（C#/.NET 8 WPF：安装 GUI + 配置 + 诊断）
├── docker-compose.yml      # 一体化部署编排（Docker Compose，推荐）
├── .env.example            # 部署环境变量模板（复制为 .env 使用）
├── .dockerignore           # 容器构建上下文排除规则
└── .gitignore
```

## 核心能力

- 内存篡改检测（驱动级扫描 + JVM 字节码校验）
- 进程 / 模块监控（内核回调 + 注入检测）
- 输入设备监控（IRP 拦截 + 点击时序分析）
- USB 设备检测（设备 DNA 画像 + HID 描述符分析）
- 游戏行为检测（移动 / 战斗 / 交互数据采集 + AI 分析）
- 基岩版外挂检测（CE 专项 + 作弊客户端特征库）
- Java 版外挂检测（JVM 探针 + Forge/Fabric 模组检测 + 作弊客户端特征库）
- 红屏强制警告（全屏高优先级覆盖 + 键盘强制暂停 + 全在线广播）
- PTEID 独立账号体系（与游戏账号完全解耦）
- 管理员远程查端（WebRTC 屏幕查看 + 证据远程提取 + 远程解锁）
- 本地加密持久化（AES-256 + SHA-256 哈希链，重启后保留）

## 三层架构

1. **玩家端检测层**：安装于玩家设备（Windows/Android/iOS/iPadOS/HarmonyOS/Linux/macOS），内核驱动 + JVM 探针采集本地全维度数据
2. **双端适配层**：统一基岩版与 Java 版的检测数据格式与通信协议
3. **PTV 管控层**：部署于 PTV（PotatoTV）服务器，负责 AI 分析、红屏广播、PTEID 账号管理、远程查端

**架构约束（不可变更）**：
- 系统完全无法封禁玩家，仅能红屏警告 + 强制暂停 + 远程查端 + 永久记录
- 系统完全无法获取游戏服务器数据，所有数据仅来自玩家本地采集
- PTV 与游戏服务器无任何连接，管理后台部署于 PTV 服务器

## 构建与运行

> 本机默认工具链为 JDK 8，运行本工程需 **JDK 21 + Maven**，前端需 **Node.js 18+**。

### PTV 管理后端（Java 21 + Spring Boot）

```bash
cd ptv-backend
mvn clean package
java -jar target/ptv-backend-4.0.0.jar
```

### 管理后台前端（React + Vite）

```bash
cd ptv-frontend
npm install
npm run dev          # 开发模式，默认 http://localhost:5173
```

### 一体化部署（Docker Compose，推荐）

> 依赖：Docker 24+ / Docker Compose v2。构建阶段自动使用华为云 Maven 镜像与 npmmirror 加速。

```bash
# 1. 准备环境变量（生产务必修改全部密钥）
copy .env.example .env          # Windows
# cp .env.example .env          # Linux/macOS

# 2. 构建并启动（MySQL + PTV 后端 + 管理后台前端）
docker compose up -d --build

# 3. 查看健康状态（全部 healthy 即就绪）
docker compose ps

# 4. 验证服务可用
curl http://localhost:8080/actuator/health        # 后端 -> {"status":"UP"}
curl http://localhost:8081/healthz                # 前端 -> ok
curl -X POST http://localhost:8081/api/admin/login \
     -H 'Content-Type: application/json' \
     -d '{"admin_key":"pacc-admin-secret-key"}'   # 管理登录

# 5. 查看日志 / 停止
docker compose logs -f ptv-backend
docker compose down                               # 保留数据卷
docker compose down -v                            # 连数据一并清除
```

**访问入口**：

| 服务 | 地址 | 说明 |
|---|---|---|
| 管理后台前端 | http://localhost:8081 | 登录密钥见 `.env` 中 `PACC_ADMIN_API_KEY` |
| PTV 后端 REST | http://localhost:8080 | `/actuator/health` 健康检查 |
| 玩家端 WSS | ws://localhost:8080/ws/ptv | 玩家端长连接（经前端网关亦可） |

**演示数据**：`PACC_SEED=true` 时后端启动即预置 3 个反作弊账号
（`demo@ptv.dev / DemoPass123` 等，见 `ptv-backend/.../bootstrap/DataSeeder.java`）。
玩家端 `ptv-client` 默认即用该账号自动登录换取真实 JWT 后连接 WSS。

### 玩家端用户态服务（Java 21）

```bash
cd ptv-client
mvn clean package
java -jar target/ptv-client-4.0.0.jar       # 配置见 src/main/resources/pacc-client.properties
```

> 玩家端演示模式会调用 `POST /api/auth/login` 自动换取真实 JWT（替代无效的 `demo-access-token`），
> 再以该令牌建立 WSS 长连接；断线后按配置间隔自动重连。

### 通信协议生成（proto → Java）

统一协议定义于 `proto/pacc.proto`（Protocol Buffers 3）。按需用 `protoc` 生成客户端/服务端绑定：

```bash
# Java（后端 / 玩家端 / Java 探针）
protoc --java_out=ptv-backend/src/main/java proto/pacc.proto

# 其他语言（Python / C++ / Go 等）同理替换 --<lang>_out
protoc --python_out=ptv-client/.. proto/pacc.proto
```

> 当前演示实现使用轻量 JSON 消息（见 `ptv-client` 的 `Json` 与后端 `PlayerWebSocketHandler`），
> 与 `pacc.proto` 的消息结构一一对应；生产接入可平滑切换为 protobuf 二进制帧。

## 模块说明

| 模块 | 路径 | 技术栈 | 状态 |
|---|---|---|---|
| PTV 管控后端 | `ptv-backend` | Java 21 / Spring Boot 3 | ✅ 核心实现 |
| 玩家端用户态服务 | `ptv-client` | Java 21 | ✅ 核心实现 |
| 管理后台前端 | `ptv-frontend` | TypeScript / React / Vite | ✅ 核心实现 |
| 统一通信协议 | `proto/pacc.proto` | Protocol Buffers | ✅ 核心实现 |
| Java 版探针 | `platform/java-agent` | Java 21 / Instrumentation | ✅ 完整实现 |
| Windows 内核驱动 | `platform/kernel-windows` | C / CDP | ✅ 完整实现 |
| Linux 内核模块 | `platform/kernel-linux` | C / Kprobe | ✅ 完整实现 |
| Linux 用户态守护 | `platform/linux-daemon` | C | ✅ 完整实现 |
| Android 客户端 | `platform/android` | NDK (C++) | ✅ 完整实现 |
| iOS/iPadOS 客户端 | `platform/ios` | Swift | ✅ 完整实现 |
| HarmonyOS 客户端 | `platform/harmony` | ArkTS | ✅ 完整实现 |
| Go 边缘网关 | `deploy/gateway-go` | Go | ✅ 完整实现（限流 + 鉴权 + 指标） |
| AI 推理服务 | `deploy/ai` | Python 3.11 / FastAPI / NumPy | ✅ 完整实现（评分 + 训练 + 健康检查） |
| Rust 数据管道 | `deploy/pipe-rust` | Rust | ✅ 完整实现（指纹 / 签名 / 聚合 / 加密） |
| Lua 动态规则引擎 | `ptv-backend/.../rule/LuaRuleEngine` | Lua + Luaj | ✅ 完整实现（热更新规则） |
| 监控栈 | `deploy/monitoring` | Prometheus + Grafana | ✅ 完整实现（compose profile） |
| Windows 管理工具 | `tools/windows-gui` | C# / .NET 8 WPF | ✅ 完整实现（安装/配置/诊断） |
| 部署编排 | `docker-compose.yml` + `deploy/` | Docker / Compose / Helm / K8s | ✅ 完整实现 |

## 技术栈与环境清单覆盖（详见 `Untitled.md`）

| 维度 | 覆盖情况 |
|---|---|
| 主语言 Java 21 LTS | `ptv-backend` / `ptv-client` / `platform/java-agent`（Spring Boot 3 / Maven） |
| C / C++ | `platform/kernel-windows`、`kernel-linux`、`linux-daemon`、`android`（NDK） |
| Rust | `deploy/pipe-rust`（零依赖标准库实现 SHA-256 / HMAC / AES-256） |
| Python 3.11 | `deploy/ai`（FastAPI + NumPy） |
| Kotlin / Swift / ArkTS | `platform/android`、`platform/ios`、`platform/harmony` |
| C#（Windows 工具） | `tools/windows-gui`（.NET 8 WPF） |
| Go（服务端网关） | `deploy/gateway-go`（HTTP 反代 + 令牌桶限流 + Prometheus） |
| TypeScript / React | `ptv-frontend`（Vite + ECharts 可视化） |
| Lua（动态规则） | `ptv-backend` 规则引擎 + `rules/*.lua` 热更新 |
| PowerShell / Bash | `tools/windows-gui/deploy/installer.ps1` 等部署脚本 |
| 数据库 | MySQL 8（生产，Compose/Helm/K8s 编排）、H2（开发内置） |
| 消息 / 缓存 / 时序 | 以可替换接口预留（REST/WSS + 事件流，生产可接入 Redis/Kafka/ClickHouse） |
| 容器编排 | Docker Compose（默认）、Kubernetes（`deploy/k8s`）、Helm（`deploy/helm`） |
| 可观测性 | Prometheus + Grafana（`deploy/monitoring`，`--profile monitoring` 启用） |
| 安全 | bcrypt 密码哈希、HMAC-SHA256 上报签名、AES-256 本地加密、JWT、TLS 1.3/WSS |

## 许可证

GNU Affero General Public License v3.0（AGPLv3）
