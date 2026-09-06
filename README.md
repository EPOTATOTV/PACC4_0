# PACC

PACC（Professional Anti-Cheat Client）是面向 Minecraft 基岩版与 Java 版的玩家端反作弊系统，采用「玩家本地检测 + PTV 管控」架构。系统本身**不具备封禁玩家的能力**，只通过本地检测、记录留痕、红屏强制警告、玩家独立账号体系与管理员远程查端进行威慑与取证。

## 三条架构约束

- **不封禁玩家**：只做本地检测与记录、红屏警告、远程查端，不接入也不开发任何游戏服务器封禁能力。
- **只取玩家本地数据**：所有检测数据都来自玩家设备本机采集，不接入任何游戏服务器 API 或数据库。
- **管理后台独立部署**：管理面板部署于 PTV 自有服务器，与游戏服务器物理隔离。

## 核心能力

- 本地检测：内存篡改、进程 / 模块、输入设备、USB / HID 设备等维度的检测，双端覆盖（基岩版 + Java 版）
- AI 行为分析：对行为数据建模，按置信度分级处理
- 红屏强制警告：全屏提示 + 键盘锁定 + 全在线广播
- PTEID 独立账号体系：与游戏账号解耦，带信誉评分与检测记录
- 管理员远程查端：远程查看屏幕、提取证据、解锁红屏
- 本地加密持久化：关键配置在本地加密保存，重启后保留

## 目录结构

```
d:\pacc\
├── proto/                  # 通信协议定义（Protocol Buffers）
├── ptv-backend/            # PTV 管控后端（Java 21 + Spring Boot 3）
├── ptv-frontend/           # 管理后台前端（TypeScript + React + Vite）
├── ptv-client/             # 玩家端用户态服务（Java 21）
├── platform/               # 平台模块（内核/Java Agent/移动端/Linux）
├── deploy/                 # 部署编排（网关/下载站/AI/监控/Helm/K8s/单机脚本）
├── tools/
│   ├── windows-gui/        # Windows 管理工具（C#/.NET WPF：安装/诊断/探针更新）
│   └── installer/          # Inno Setup 安装向导
├── docker-compose.yml      # 一体化部署编排（推荐）
├── .env.example            # 部署环境变量模板
└── .github/                # CI/CD 流水线（本地开发用）
```

## 构建与运行

> 后端与玩家端需 **JDK 21 + Maven**，前端需 **Node.js 18+**。

### 管理后台后端（Java 21 + Spring Boot）

```bash
cd ptv-backend
mvn clean package
java -jar target/ptv-backend-*.jar
```

### 管理后台前端（React + Vite）

```bash
cd ptv-frontend
npm install
npm run dev            # 开发模式，默认 http://localhost:5173
```

### 玩家端用户态服务（Java 21）

```bash
cd ptv-client
mvn clean package
java -jar target/ptv-client-*.jar
# 配置见 src/main/resources/pacc-client.properties
```

### 一体化部署（Docker Compose，推荐）

```bash
# 1. 准备环境变量（生产务必修改全部密钥）
copy .env.example .env           # Windows
# cp .env.example .env           # Linux/macOS

# 2. 构建并启动（MySQL + 后端 + 管理后台前端）
docker compose up -d --build

# 3. 查看健康状态（全部 healthy 即就绪）
docker compose ps

# 4. 验证
curl http://localhost:8080/actuator/health      # 后端
curl http://localhost:8081/healthz              # 前端

# 5. 查看日志 / 停止
docker compose logs -f ptv-backend
docker compose down
```

**访问入口**：

| 服务 | 地址 | 说明 |
|---|---|---|
| 管理后台前端 | http://localhost:8081 | 登录密钥见 `.env` 中配置项 |
| 后端 REST | http://localhost:8080 | `/actuator/health` 健康检查 |
| 玩家端 WSS | ws://localhost:8080/ws/ptv | 玩家长连接 |

部署到公网时，官方入口为四个子域，经统一网关转发：

| 用途 | 域名 |
|---|---|
| 管理后台 | admin.YourDomain |
| API / WebSocket | api.YourDomain |
| 客户端下载站 | dl.YourDomain |
| 玩家连接 | pacc.YourDomain |

## 数据库

- 生产默认 **MySQL 8**，表结构由 Flyway 版本化迁移管理（`ptv-backend/src/main/resources/db/migration/`）。
- 本地开发 profile 使用 H2 内存库，无需手动建表。

## 环境变量

部署相关密钥与配置一律通过环境变量 / `.env` 注入（模板见 `.env.example`），不写入源码。生产环境请务必使用随机强密钥覆盖默认值。

## 许可证

GNU Affero General Public License v3.0（AGPLv3）