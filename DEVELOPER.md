# PACC 开发者文档（API 与整体）

> 供前后端开发、第三方接入方查阅。本文只描述**功能与调用方式**，不涉及内部检测逻辑与敏感配置。
> 生产请以实际部署环境与 `.env` 配置为准；本地联调默认 `localhost:8080`（后端）、`localhost:8081`（管理后台前端）、`localhost:5173`（前端 dev server）。

## 目录

- [系统组成](#系统组成)
- [本地开发环境](#本地开发环境)
- [鉴权方式](#鉴权方式)
  - [玩家端](#玩家端)
  - [管理后台](#管理后台)
  - [开放 API（/api/v1）](#开放-api-apiv1)
- [API 参考](#api-参考)
- [分页与错误约定](#分页与错误约定)
- [Webhook](#webhook)
- [前端开发](#前端开发)
- [部署概述](#部署概述)
- [安全提示](#安全提示)

## 系统组成

| 模块 | 路径 | 技术栈 | 说明 |
|---|---|---|---|
| PTV 管控后端 | `ptv-backend` | Java 21 / Spring Boot 3 | REST + WebSocket，管理端与玩家端 API |
| 管理后台前端 | `ptv-frontend` | TypeScript / React / Vite | 管理后台 UI |
| 玩家端服务 | `ptv-client` | Java 21 | 玩家侧用户态服务 |
| 通信协议 | `proto/pacc.proto` | Protocol Buffers | 玩家长连接消息结构 |
| 部署编排 | `docker-compose.yml` + `deploy/` | Docker / Helm / K8s | 容器化部署 |

## 本地开发环境

前置：**JDK 21 + Maven**、**Node.js 18+**。

```bash
# 后端（默认 H2 内存库，无需 MySQL）
cd ptv-backend
mvn spring-boot:run

# 管理后台前端
cd ptv-frontend
npm install
npm run dev
```

- 后端健康检查：`GET http://localhost:8080/actuator/health`
- 数据库：生产用 MySQL 8（Flyway 迁移，脚本在 `ptv-backend/src/main/resources/db/migration/`）；本地 profile 用 H2。
- 演示数据：本地以 `PACC_SEED=true` 启动可预置反作弊演示账号（见启动日志，仅本地）。

## 鉴权方式

系统有三套隔离的鉴权，按路由前缀区分。

### 玩家端

- 路由前缀：`/api/auth`（注册/登录）、`/api/player`（玩家自助门户）。
- 登录/注册成功后，服务端通过**会话 Cookie**（HttpOnly）下发登录态；后续 `/api/player/**` 请求自动携带 Cookie 即通过鉴权。
- 未登录访问 `/api/player/**` 返回 `401`。

关键接口（`POST /api/auth`）：

| 接口 | 说明 | 关键字段 |
|---|---|---|
| `POST /api/auth/code/send` | 发送验证码 | `target`, `scene` |
| `POST /api/auth/code/verify` | 校验验证码 | `target`, `scene`, `code` |
| `POST /api/auth/register` | 玩家注册 | 账号邮箱、验证码、密码、设备指纹等 |
| `POST /api/auth/login` | 玩家登录 | 邮箱 + 密码 |
| `POST /api/auth/reset` | 重置密码 | `token`, `new_password` |
| `POST /api/auth/logout` / `GET /api/auth/me` | 退出 / 查看登录态 | — |

### 管理后台

- 路由前缀：`/api/admin/**`。
- 认证方式：请求头 `X-Admin-Key` 或登录后下发的**会话 Cookie**，二选一。
- 除 `login`、`me`、`logout`、`feishu` 入口外，其余 `/api/admin/**` 均要求已认证，否则返回 `401`。

| 接口 | 说明 |
|---|---|
| `POST /api/admin/login` | 管理后台登录（`admin_key`），成功后写会话 Cookie |
| `GET /api/admin/me` / `POST /api/admin/logout` | 查看登录态 / 退出 |
| `GET /api/admin/feishu/oauth/url` | 获取飞书授权跳转地址 |
| `POST /api/admin/feishu/oauth/callback` | 飞书登录回调（OAuth code + state） |

### 开放 API（/api/v1）

- 供第三方以 **API Key + HMAC-SHA256 签名** 调用，隔离于玩家/管理会话。
- 需服务端先为租户签发 API Key（见 `POST /api/admin/api/...`，多租户节点）。

**必带请求头**：

| 请求头 | 含义 |
|---|---|
| `X-PTV-Key` | API Key ID |
| `X-PTV-Timestamp` | 请求时间（毫秒时间戳），与服务端时钟差须在允许窗口内 |
| `X-PTV-Nonce` | 一次性随机串，防重放（重复 nonce 会被拒绝） |
| `X-PTV-Signature` | 签名（十六进制小写） |

**签名算法**：

```text
canonical   = "{METHOD}\n{PATH}\n{TIMESTAMP}\n{BODY_SHA256_HEX}"
signature   = HEX( HMAC-SHA256( secret, canonical ) )
```

其中 `PATH` 为请求路径（不含 query），`BODY_SHA256_HEX` 为请求体原始字节的 SHA-256 十六进制（GET 无体时为空，空串的 SHA-256）。`secret` 为与该 Key 配套的密钥，双方预先约定，**仅存在服务端加密存储**，勿下发到客户端。

**curl 示例**（伪代码示意字段）：

```bash
TS=$(date +%s%3N)
BODY=""                                  # GET 场景
COMP=${BODY:+$(printf %s "$BODY" | sha256sum | awk '{print $1}')}
CANON="$METHOD\n$PATH\n$TS\n$COMP"
SIG=$(printf %b "$CANON" | openssl dgst -sha256 -hmac "$SECRET" -hex | awk '{print $2}')
curl -i "http://localhost:8080/api/v1/detections?page=0&size=10" \
  -H "X-PTV-Key: $KEY" \
  -H "X-PTV-Timestamp: $TS" \
  -H "X-PTV-Nonce: $(uuidgen)" \
  -H "X-PTV-Signature: $SIG"
```

拦截结果：

- `401` 签名/时间戳/nonce 校验失败
- `403` 调用方 IP 不在白名单，或写权限不足
- `429` 超出限流

## API 参考

按功能域分组。路径前缀以 `RequestMapping` 级注释为准；典型接口如下（非全量，完整以代码为准）。

### 玩家认证与门户

| 方法/路径 | 说明 |
|---|---|
| `POST /api/auth/register` | 玩家注册 |
| `POST /api/auth/login` | 玩家登录 |
| `POST /api/auth/code/send` | 发送验证码 |
| `POST /api/auth/code/verify` | 校验验证码 |
| `POST /api/auth/reset` | 重置密码 |
| `GET /api/player/me` | 当前玩家信息 |
| `GET /api/player/summary` | 玩家概览（检测/信誉/工单等汇总） |
| `GET /api/player/records` | 检测记录 |
| `POST /api/player/appeals` / `GET /api/player/appeals` | 提交 / 查询申诉 |
| `POST /api/player/tickets` | 提交客服工单 |
| `GET /api/player/tickets` / `GET /api/player/tickets/{id}` | 工单列表 / 详情 |
| `POST /api/player/countermeasure/integrity` | 上报客户端完整性校验结果 |
| `POST /api/player/countermeasure/environment` | 上报环境检测结果 |

### 管理后台（/api/admin/**，需 X-Admin-Key 或会话 Cookie）

| 域 | 路径前缀 | 能力 |
|---|---|---|
| 管理认证 | `/api/admin` | 登录 / 登出 / 我 / 飞书 SSO |
| 账号管理 | `/api/admin/accounts` | 反作弊账号查询（敏感字段脱敏）、信誉调整 |
| 检测记录 | `/api/admin/records` | 检测事件查询 |
| 红屏 | `/api/admin/redscreens` | 红屏事件列表/查询（`state` 参数，默认待处理） |
| 检测看板 | `/api/admin/stats` | 统计与大盘数据 |
| 特征库 | `/api/admin/signatures` | 特征增删改查、灰度发布、回滚 |
| 规则引擎 | `/api/admin/rules` | 动态检测规则（如 Lua 规则）管理 |
| 远程查端 | `/api/admin/inspects` | 设备远程检视会话 |
| 反制策略 | `/api/admin/countermeasure` | 客户端反制/校验 |
| 赛事风控 | `/api/admin/competition` | 报名、审批、分队、对局、IP 聚类、赛程 |
| 客服支持 | `/api/admin/support` | 工单/客服闭环 |
| 运维 | `/api/admin/ops` | 运维相关操作 |
| A/B 实验 | `/api/admin/ab` | 实验管理 |
| 多租户 | `/api/admin/tenant` | 租户分级、租户管理员绑定 |
| 审计 | `/api/admin/audit` | 管理端操作审计（列表/趋势/大盘） |
| BI | `/api/admin/bi` | 报表数据源聚合 |
| 合规 | `/api/admin/compliance` | 合规模块 |
| 威胁情报 v4.6 | `/api/admin/v46` | 零日评估、特征库、主动学习队列 |
| 威胁情报 v4.7 | `/api/admin/v47` | 家族谱系、主动威慑策略、IOC 管理 |
| 历史版本 | `/api/admin/v41` | 旧版管理接口 |
| 开放 API 密钥 | `/api/admin/api` | API Key 签发与 CRUD |

常见审计接口：

| 方法/路径 | 说明 |
|---|---|
| `GET /api/admin/audit/operations` | 操作审计分页查询（可按操作人/动作/时间过滤） |
| `GET /api/admin/audit/trend` | 审计趋势 |
| `GET /api/admin/audit/overview` | 大盘（状态码分布、TOP 操作） |

### 玩家端扩展接口

| 域 | 路径前缀 | 能力 |
|---|---|---|
| 玩家运维 | `/api/player/ops` | 玩家侧运维行为 |
| 威胁情报(v4.6) | `/api/player/v46` | 玩家侧情报相关上报 |
| 赛事 | `/api/player/competition` | 报名状态、赛程查看、入场校验 |

### 开放 API（/api/v1，API Key + HMAC，详见上文）

| 方法/路径 | 说明 |
|---|---|
| `GET /api/v1/detections` | 检测记录分页（`page`/`size`） |
| `GET /api/v1/detections/{recordId}` | 检测详情 |
| `GET /api/v1/redscreen/events` | 红屏事件 |
| `GET /api/v1/redscreen/active` | 当前活跃红屏 |
| `POST /api/v1/redscreen/{alertId}/unlock` | 远程解锁红屏（需写权限） |
| `GET /api/v1/players/{pteid}/reputation` | 玩家信誉分 |
| `GET /api/v1/policy` | 当前策略 |
| `GET /api/v1/signatures` | 特征列表 |
| `GET /api/v1/stats/overview` | 概览统计 |

## 分页与错误约定

- 列表接口通用 `page`（从 0 起）+ `size`（每页条数）查询参数。
- 错误通过 **HTTP 状态码**表达：

| 状态码 | 含义 |
|---|---|
| `200` | 成功 |
| `401` | 未认证 / 令牌无效 / 开放 API 签名错误 |
| `403` | 无权限 / 来源受限 |
| `404` | 资源不存在 |
| `429` | 触发限流 |
| `5xx` | 服务端错误（响应体为通用错误信息，不含堆栈） |

- 出于安全，鉴权失败与登录失败统一返回通用提示，不暴露账号存在性等内部信息。

## Webhook

开放 API 支持事件推送（如红屏、检测告警）。服务端将事件推送到租户配置的 URL，且对 payload 做 **HMAC-SHA256 签名**（用该租户的 `webhookSecret`），接收方按同样方式复核签名即防篡改、防伪造来源。具体事件类型与字段以实际代码为准。

## 前端开发

- 技术栈：React + TypeScript + Vite，状态与 API 封装见 `ptv-frontend/src/api/`。
- 路由与菜单在 `src/` 的导航/路由配置中登记；新增页面需同步加路由、菜单与（可选）多语言文案。
- 管理后台通过 `/api/admin/**` 交互；登录态走 Cookie（浏览器自动带上），前端不再把令牌写 `localStorage`。

## 部署概述

生产建议使用 `docker-compose.yml` 一体化编排，经统一网关对外，按用途分四个子域（管理后台 / API / 下载站 / 玩家连接）。完整步骤与 `.env` 模板见根目录 `README.md` 与 `.env.example`。

## 安全提示

- 所有密钥（JWT、管理密钥、WSS 签名密钥、第三方凭据等）一律经环境变量 / `.env` 注入，**切勿提交**。
- 生产环境：`ddl-auto=validate`（防表结构漂移），日志默认 `INFO`。
- 提交前请扫描仓库是否残留证书、私钥、凭据与内部设计文档（见 `.gitignore`）。