# POTATOTV Anti-Cheat Client

> 面向 Minecraft 基岩版与 Java 版的全平台玩家端反作弊系统。

[![License](https://img.shields.io/badge/license-AGPL--3.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.x-green.svg)](https://spring.io/projects/spring-boot)
[![React](https://img.shields.io/badge/React-18-61DAFB.svg)](https://react.dev)
[![Docker](https://img.shields.io/badge/Docker-Compose-2496ED.svg)](https://docs.docker.com/compose/)

## 📖 项目简介

随着 Minecraft 基岩版在全球多平台的普及，作弊行为呈现出跨平台、多样化、隐蔽化的趋势。传统反作弊方案往往只支持单一平台，无法满足企业级赛事与服务器运营的全平台防护需求。

PACC（POTATOTV Anti-Cheat Client）采用 **统一核心引擎 + 平台适配层** 的架构设计，实现一套核心逻辑在 Windows、Android、iOS、iPadOS、HarmonyOS 等主流操作系统上的高效运行。系统采用「玩家本地检测 + PTV 管控」架构，**本身不具备封禁玩家的能力**，只通过本地检测、记录留痕、红屏强制警告、玩家独立账号体系与管理员远程查端进行威慑与取证。

## ✨ 核心特性

- 🛡 **本地检测**：内存篡改、进程 / 模块、输入设备、USB / HID 设备等多维度检测，双端覆盖（基岩版 + Java 版）
- 🧠 **AI 行为分析**：对行为数据建模，按置信度分级处理
- 🚨 **红屏强制警告**：全屏提示 + 键盘锁定 + 全在线广播
- 🪪 **PTEID 独立账号体系**：与游戏账号解耦，带信誉评分与检测记录
- 🖥 **管理员远程查端**：远程查看屏幕、提取证据、解锁红屏
- 🔐 **本地加密持久化**：关键配置在本地加密保存，重启后保留

## 🏗 架构设计

PACC 遵循三条核心架构约束：

- **不封禁玩家**：只做本地检测与记录、红屏警告、远程查端，不接入也不开发任何游戏服务器封禁能力
- **只取玩家本地数据**：所有检测数据都来自玩家设备本机采集，不接入任何游戏服务器 API 或数据库
- **管理后台独立部署**：管理面板部署于 PTV 自有服务器，与游戏服务器物理隔离

## 🛠 技术栈

| 层级 | 技术 |
|------|------|
| 后端 | Java 21 + Spring Boot 3 |
| 前端 | TypeScript + React + Vite |
| 通信协议 | Protocol Buffers（WSS 长连接） |
| 数据库 | MySQL 8（生产）/ H2（本地开发） |
| 数据库迁移 | Flyway |
| 桌面工具 | C# / .NET WPF |
| 安装向导 | Inno Setup |
| 移动端 | Android 探针 + Rust 管道 |
| 容器化 | Docker Compose |
| CI/CD | GitHub Actions + CodeQL |

## 📦 目录结构

```

pacc/

├── proto/                  # 通信协议定义（Protocol Buffers）

├── ptv-backend/            # PTV 管控后端（Java 21 + Spring Boot 3）

├── ptv-frontend/           # 管理后台前端（TypeScript + React + Vite）

├── ptv-client/             # 玩家端用户态服务（Java 21）

├── ptv-desktop/            # 桌面壳（C#/.NET WPF）

├── ptv-mobile/             # 移动端探针

├── platform/               # 平台模块（内核/Java Agent/移动端/Linux）

├── deploy/                 # 部署编排（网关/下载站/AI/监控/Helm/K8s/单机脚本）

├── sdk/                    # 插件开发 SDK

├── tools/

│   ├── windows-gui/        # Windows 管理工具（C#/.NET WPF：安装/诊断/探针更新）

│   └── installer/          # Inno Setup 安装向导

├── docker-compose.yml      # 一体化部署编排（推荐）

├── .env.example            # 部署环境变量模板

└── .github/                # CI/CD 流水线

```
## 🚀 快速开始

### 环境要求

- **JDK 21 + Maven**（后端与玩家端）
- **Node.js 18+**（前端）
- **Docker + Docker Compose**（一体化部署）

### 管理后台后端

```bash
cd ptv-backend
mvn clean package
java -jar target/ptv-backend-*.jar
```

### 管理后台前端

```bash
cd ptv-frontend
npm install
npm run dev
# 开发模式，默认 http://localhost:5173
```

### 玩家端用户态服务

```bash
cd ptv-client
mvn clean package
java -jar target/ptv-client-*.jar
# 配置见 src/main/resources/pacc-client.properties
```

### 一体化部署（Docker Compose，推荐）

```bash
# 1. 准备环境变量（生产务必修改全部密钥）
cp .env.example .env

# 2. 构建并启动（MySQL + 后端 + 管理后台前端）
docker compose up -d --build

# 3. 查看健康状态（全部 healthy 即就绪）
docker compose ps

# 4. 验证
curl http://localhost:8080/actuator/health   # 后端
curl http://localhost:8081/healthz           # 前端

# 5. 查看日志 / 停止
docker compose logs -f ptv-backend
docker compose down
```

### 访问入口

| 服务 | 地址 | 说明 |
|------|------|------|
| 管理后台前端 | http://localhost:8081 | 登录密钥见 `.env` |
| 后端 REST | http://localhost:8080 | `/actuator/health` 健康检查 |
| 玩家端 WSS | ws://localhost:8080/ws/ptv | 玩家长连接 |

部署到公网时，官方入口为四个子域，经统一网关转发：

| 用途 | 域名 |
|------|------|
| 管理后台 | `admin.your-domain.com` |
| API / WebSocket | `api.your-domain.com` |
| 客户端下载站 | `dl.your-domain.com` |
| 玩家连接 | `pacc.your-domain.com` |

## 🗄 数据库

- **生产环境**默认使用 **MySQL 8**，表结构由 Flyway 版本化迁移管理（`ptv-backend/src/main/resources/db/migration/`）
- **本地开发** profile 使用 **H2 内存库**，无需手动建表

## ⚙️ 环境变量

部署相关密钥与配置一律通过环境变量 / `.env` 注入（模板见 `.env.example`），不写入源码。**生产环境请务必使用随机强密钥覆盖默认值**。

| 变量 | 说明 | 示例 |
|------|------|------|
| `MYSQL_ROOT_PASSWORD` | MySQL root 密码 | `your-strong-password` |
| `JWT_SECRET` | JWT 签名密钥 | `random-64-chars` |
| `ADMIN_LOGIN_KEY` | 管理后台登录密钥 | `your-login-key` |
| `SERVER_DOMAIN` | 部署域名 | `admin.your-domain.com` |

## 🧪 测试

```bash
# 后端测试
cd ptv-backend && mvn test

# 前端测试
cd ptv-frontend && npm run test
```

## 🤝 贡献指南

1. Fork 本仓库
2. 创建分支 `git checkout -b feature/xxx`
3. 提交改动 `git commit -m "feat: xxx"`
4. 推送分支 `git push origin feature/xxx`
5. 提交 Pull Request

> 提交前请确保通过 CI 检查（GitHub Actions 会自动运行测试与 CodeQL）。

## 📄 许可证

本项目基于 [GNU Affero General Public License v3.0（AGPLv3）](LICENSE) 开源。

## 🙏 致谢

- [Spring Boot](https://spring.io/projects/spring-boot)
- [React](https://react.dev)
- [Vite](https://vitejs.dev)
- [Flyway](https://flywaydb.org)